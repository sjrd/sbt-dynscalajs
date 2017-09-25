package be.doeraene.sbtdynscalajs

import scala.language.reflectiveCalls

import sbt._
import sbt.Keys._

import sbtcrossproject.CrossPlugin.autoImport._

object DynScalaJSPlugin extends AutoPlugin {
  override def requires: Plugins = plugins.JvmPlugin

  object autoImport {
    val dynScalaJSVersion: SettingKey[Option[String]] =
      settingKey[Option[String]]("the version of Scala.js to use, or None for the JVM")

    val dynScalaJSLinkerClassLoader: TaskKey[ClassLoader] =
      taskKey[ClassLoader]("a ClassLoader loaded with the Scala.js linker API")

    val dynScalaJSLinker: TaskKey[AnyRef] =
      taskKey[AnyRef]("instance of the Scala.js linker")

    val fastOptJS: TaskKey[Attributed[File]] =
      taskKey[Attributed[File]]("fastOptJS")
  }

  import autoImport._

  private def loadModule(classLoader: ClassLoader, moduleName: String): AnyRef =
    classLoader.loadClass(moduleName + "$").getField("MODULE$").get(null)

  private def newInstance(classLoader: ClassLoader, className: String): AnyRef =
    classLoader.loadClass(className).newInstance().asInstanceOf[AnyRef]

  private def invokeMethod(instance: AnyRef, methodName: String, args: Any*): Any = {
    val m = instance.getClass.getDeclaredMethods().find { m =>
      m.getName == methodName &&
      m.getParameterCount == args.size &&
      m.getParameterTypes.zip(args).forall {
        case (formal, actual) =>
          formal.isPrimitive() || formal.isInstance(actual)
      }
    }.get
    m.invoke(instance, args.asInstanceOf[Seq[AnyRef]]: _*)
  }

  def scalaJSLogLevel2sbtLogLevel(classLoader: ClassLoader, level: AnyRef): Level.Value = {
    level.toString() match {
      case "Error" => Level.Error
      case "Warn"  => Level.Warn
      case "Info"  => Level.Info
      case "Debug" => Level.Debug
    }
  }

  def sbtLogger2scalaJSLogger(classLoader: ClassLoader, logger: Logger): AnyRef = {
    import java.lang.reflect.{InvocationHandler, Method, Proxy}

    val scalaJSLoggerClass = classLoader.loadClass(
        "org.scalajs.core.tools.logging.Logger")

    val handler = new InvocationHandler {
      def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = {
        def message(argIndex: Int): String = {
          val messageFun = args(argIndex).asInstanceOf[{ def apply(): Object }]
          messageFun.apply().asInstanceOf[String]
        }

        method.getName match {
          case "log" =>
            val level = scalaJSLogLevel2sbtLogLevel(classLoader, args(0))
            logger.log(level, message(1))
            null
          case "success" =>
            logger.success(message(0))
            null
          case "trace" =>
            val throwableFun = args(0).asInstanceOf[{ def apply(): Object }]
            logger.trace(throwableFun.apply().asInstanceOf[Throwable])
            null
          case "error" =>
            logger.error(message(0))
            null
          case "warn" =>
            logger.warn(message(0))
            null
          case "info" =>
            logger.info(message(0))
            null
          case "debug" =>
            logger.debug(message(0))
            null
          case "hashCode" =>
            System.identityHashCode(proxy): java.lang.Integer
          case "equals" =>
            (proxy eq args(0)): java.lang.Boolean
          case "toString" =>
            s"WrappedLogger(${logger.toString()})"
          case "time" if args(1).isInstanceOf[Long] =>
            val title = args(0).asInstanceOf[String]
            val nanos = args(1).asInstanceOf[Long]
            logger.debug(s"$title: ${nanos / 1000} us")
            null
          case "time" =>
            val title = args(0).asInstanceOf[String]
            val bodyFun = args(1).asInstanceOf[{ def apply(): Object }]
            val startTime = System.nanoTime()
            val result = bodyFun()
            val endTime = System.nanoTime()
            val elapsedTime = endTime - startTime
            logger.debug(s"$title: ${elapsedTime / 1000} us")
            result
        }
      }
    }

    Proxy.newProxyInstance(classLoader, Array(scalaJSLoggerClass), handler)
  }

  def seq2scalaJSSeq(classLoader: ClassLoader, seq: Seq[Any]): AnyRef = {
    val nil = loadModule(classLoader, "scala.collection.immutable.Nil")
    seq.foldRight(nil) { (elem, prev) =>
      prev.asInstanceOf[{ def ::(x: Any): Object }].::(elem)
    }
  }

  override def globalSettings: Seq[Setting[_]] = Seq(
      dynScalaJSVersion := None,

      Def.derive {
        dynScalaJSLinkerClassLoader := {
          val scalaJSVersion = dynScalaJSVersion.value.getOrElse {
            throw new MessageOnlyException(
                "The current project is currently configured for the JVM. " +
                "Cannot retried the Scala.js linker ClassLoader.")
          }
          val s = streams.value
          val log = s.log
          val retrieveDir = s.cacheDirectory / "scalajs-linker" / scalaJSVersion
          val lm = {
            import sbt.librarymanagement.ivy._
            val ivyConfig = InlineIvyConfiguration().withLog(log)
            IvyDependencyResolution(ivyConfig)
          }
          val maybeFiles = lm.retrieve(
              "org.scala-js" % "scalajs-tools_2.12" % scalaJSVersion,
              scalaModuleInfo = None, retrieveDir, log)
          val files = maybeFiles.fold({ unresolvedWarn =>
            throw unresolvedWarn.resolveException
          }, { files =>
            files
          })
          val urls = files.map(_.toURI().toURL())
          new java.net.URLClassLoader(urls.toArray, null)
        }
      }
  )

  val configSettings: Seq[Setting[_]] = Seq(
      dynScalaJSLinker := {
        val classLoader = dynScalaJSLinkerClassLoader.value

        val configMod = loadModule(classLoader,
            "org.scalajs.core.tools.linker.StandardLinker$Config")
        val config = invokeMethod(configMod, "apply")

        val stdLinkerMod = loadModule(classLoader,
            "org.scalajs.core.tools.linker.StandardLinker")
        invokeMethod(stdLinkerMod, "apply", config).asInstanceOf[AnyRef]
      },

      artifactPath in fastOptJS := {
        ((crossTarget in fastOptJS).value /
            ((moduleName in fastOptJS).value + "-fastopt.js"))
      },

      fastOptJS := {
        val s = streams.value
        val scalaJSVersion = dynScalaJSVersion.value.get
        val classLoader = dynScalaJSLinkerClassLoader.value
        val linker = dynScalaJSLinker.value
        val classpath = Attributed.data(fullClasspath.value)
        val output = (artifactPath in fastOptJS).value

        s.log.info("Fast optimizing " + output)

        val irContainerModName = {
          if (scalaJSVersion.startsWith("0.6."))
            "org.scalajs.core.tools.io.IRFileCache$IRContainer"
          else
            "org.scalajs.core.tools.io.FileScalaJSIRContainer"
        }
        val irContainerMod = loadModule(classLoader, irContainerModName)
        val scalaJSClasspathSeq = seq2scalaJSSeq(classLoader, classpath)
        val irContainers = invokeMethod(irContainerMod, "fromClasspath",
            scalaJSClasspathSeq)

        val writableFileVirtualJSFileMod = loadModule(classLoader,
            "org.scalajs.core.tools.io.WritableFileVirtualJSFile")
        val outFile = invokeMethod(writableFileVirtualJSFileMod, "apply",
            output)

        val irFileCache = newInstance(classLoader,
            "org.scalajs.core.tools.io.IRFileCache")
        val cache = invokeMethod(irFileCache, "newCache").asInstanceOf[AnyRef]
        val irFiles = invokeMethod(cache, "cached", irContainers)

        val moduleInitializers = loadModule(classLoader,
            "scala.collection.immutable.Nil")

        val logger = sbtLogger2scalaJSLogger(classLoader, s.log)

        invokeMethod(linker, "link", irFiles, moduleInitializers, outFile, logger)

        Attributed.blank(output)
      }
  )

  val compileConfigSettings: Seq[Setting[_]] = configSettings

  val testConfigSettings: Seq[Setting[_]] = configSettings

  val baseProjectSettings: Seq[Setting[_]] = Seq(
      crossPlatform := {
        val prev = crossPlatform.value
        dynScalaJSVersion.value match {
          case None    => prev
          case Some(v) => JSPlatform(v)
        }
      },

      crossTarget := {
        val prev = crossTarget.value
        dynScalaJSVersion.value match {
          case None    => prev
          case Some(v) => prev.getParentFile / (prev.getName + "-sjs-" + v)
        }
      },

      libraryDependencies ++= {
        dynScalaJSVersion.value.fold[Seq[ModuleID]] {
          Nil
        } { scalaJSVersion =>
          Seq(
              "org.scala-js" %% "scalajs-library" % scalaJSVersion,
              "org.scala-js" % "scalajs-compiler" % scalaJSVersion % "plugin" cross CrossVersion.full,
              "org.scala-js" %% "scalajs-test-interface" % scalaJSVersion % "test"
          )
        }
      }
  )

  override def projectSettings: Seq[Setting[_]] = {
    baseProjectSettings ++
    inConfig(Compile)(compileConfigSettings) ++
    inConfig(Test)(testConfigSettings)
  }
}
