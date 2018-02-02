package be.doeraene.sbtdynscalajs

import scala.language.reflectiveCalls

import scala.annotation.tailrec

import java.util.concurrent.atomic.AtomicReference

import sbt._
import sbt.Keys._

import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._

import org.scalajs.core.tools.io.{IO => _, _}

import org.scalajs.core.tools.linker.ModuleKind

import org.scalajs.jsenv._
import org.scalajs.jsenv.nodejs.NodeJSEnv

import org.scalajs.testadapter.TestAdapter

object DynScalaJSPlugin extends AutoPlugin {
  override def requires: Plugins = plugins.JvmPlugin

  @tailrec
  final private def registerResource[T <: AnyRef](
      l: AtomicReference[List[T]], r: T): r.type = {
    val prev = l.get()
    if (l.compareAndSet(prev, r :: prev)) r
    else registerResource(l, r)
  }

  private val createdTestAdapters =
    new AtomicReference[List[TestAdapter]](Nil)

  private def newTestAdapter(jsEnv: ComJSEnv, jsFiles: Seq[VirtualJSFile],
      config: TestAdapter.Config): TestAdapter = {
    registerResource(createdTestAdapters,
        new TestAdapter(jsEnv, jsFiles, config))
  }

  private def closeAllTestAdapters(): Unit =
    createdTestAdapters.getAndSet(Nil).foreach(_.close())

  object autoImport {
    // Stage values
    val FastOptStage = Stage.FastOpt
    val FullOptStage = Stage.FullOpt

    val dynScalaJSVersion: SettingKey[Option[String]] =
      settingKey[Option[String]]("the version of Scala.js to use, or None for the JVM")

    val dynScalaJSClassLoader: TaskKey[ClassLoader] =
      taskKey[ClassLoader]("a ClassLoader loaded with the Scala.js linker API")

    /** Must be a `StandardLinker.Config` from `dynScalaJSClassLoader`. */
    val scalaJSLinkerConfig: TaskKey[AnyRef] =
      taskKey[AnyRef]("Scala.js linker configuration")

    /** Must be a `Linker` from `dynScalaJSClassLoader`. */
    val scalaJSLinker: TaskKey[AnyRef] =
      taskKey[AnyRef]("instance of the Scala.js linker")

    val scalaJSUseMainModuleInitializer: SettingKey[Boolean] =
      settingKey[Boolean]("If true, adds the `mainClass` as a module initializer of the Scala.js module")

    /** Must be a `ModuleInitializer` from `dynScalaJSClassLoader`. */
    val scalaJSMainModuleInitializer: TaskKey[Option[Any]] =
      taskKey[Option[Any]]("The main module initializer, used if `scalaJSUseMainModuleInitializer` is true")

    /** Elements must be `ModuleInitializer`s from `dynScalaJSClassLoader`. */
    val scalaJSModuleInitializers: TaskKey[Seq[Any]] =
      taskKey[Seq[Any]]("Module initializers of the Scala.js application, to be called when it starts.")

    val fastOptJS: TaskKey[Attributed[File]] =
      taskKey[Attributed[File]]("fastOptJS")

    val fullOptJS: TaskKey[Attributed[File]] =
      taskKey[Attributed[File]]("fullOptJS")

    val scalaJSStage: SettingKey[Stage] =
      settingKey[Stage]("Scala.js stage used for `run`, `test`, etc.")

    val scalaJSLinkedFile: TaskKey[Attributed[File]] =
      taskKey[Attributed[File]]("fastOptJS or fullOptJS, dependending on the value of scalaJSStage")

    val jsEnv: TaskKey[JSEnv] =
      taskKey[JSEnv]("The JavaScript environment in which to run and test Scala.js applications.")

    val jsExecutionFiles: TaskKey[Seq[VirtualJSFile]] =
      taskKey[Seq[VirtualJSFile]]("All the VirtualJSFiles given to JS environments on `run`, `test`, etc.")
  }

  import autoImport._

  private def hasOldLinkerAPI(scalaJSVer: String): Boolean = {
    scalaJSVer.startsWith("0.6.") ||
    scalaJSVer == "1.0.0-M1" ||
    scalaJSVer == "1.0.0-M2"
  }

  private def linkerPackage(oldLinkerAPI: Boolean): String =
    if (oldLinkerAPI) "org.scalajs.core.tools.linker"
    else "org.scalajs.linker"

  private def ioPackage(oldLinkerAPI: Boolean): String =
    if (oldLinkerAPI) "org.scalajs.core.tools.io"
    else "org.scalajs.io"

  private def loggingPackage(oldLinkerAPI: Boolean): String =
    if (oldLinkerAPI) "org.scalajs.core.tools.logging"
    else "org.scalajs.logging"

  private def irioPackage(oldLinkerAPI: Boolean): String =
    if (oldLinkerAPI) "org.scalajs.core.tools.io"
    else "org.scalajs.linker.irio"

  private def argsConform(parameterTypes: Array[Class[_]],
      args: Seq[Any]): Boolean = {
    parameterTypes.size == args.size &&
    parameterTypes.zip(args).forall {
      case (formal, actual) =>
        formal.isPrimitive() || formal.isInstance(actual)
    }
  }

  private def loadModule(classLoader: ClassLoader, moduleName: String): AnyRef =
    classLoader.loadClass(moduleName + "$").getField("MODULE$").get(null)

  private def newInstance(classLoader: ClassLoader, className: String, args: Any*): AnyRef = {
    val c = classLoader.loadClass(className).getDeclaredConstructors().find { c =>
      argsConform(c.getParameterTypes, args)
    }.get
    c.newInstance(args.asInstanceOf[Seq[AnyRef]]: _*).asInstanceOf[AnyRef]
  }

  private def invokeMethod(instance: Any, methodName: String, args: Any*): Any = {
    val m = instance.getClass.getMethods().find { m =>
      m.getName == methodName && argsConform(m.getParameterTypes, args)
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

  def sbtLogger2scalaJSLogger(scalaJSVersion: String, classLoader: ClassLoader,
      logger: Logger): AnyRef = {

    import java.lang.reflect.{InvocationHandler, Method, Proxy}

    val oldLinkerAPI = hasOldLinkerAPI(scalaJSVersion)
    val scalaJSLoggerClass = classLoader.loadClass(
        s"${loggingPackage(oldLinkerAPI)}.Logger")

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
        dynScalaJSClassLoader := {
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
          val artifactName =
            if (hasOldLinkerAPI(scalaJSVersion)) "scalajs-tools_2.12"
            else "scalajs-linker_2.12"
          val maybeFiles = lm.retrieve(
              "org.scala-js" % artifactName % scalaJSVersion,
              scalaModuleInfo = None, retrieveDir, log)
          val files = maybeFiles.fold({ unresolvedWarn =>
            throw unresolvedWarn.resolveException
          }, { files =>
            files
          })
          val urls = files.map(_.toURI().toURL())
          new java.net.URLClassLoader(urls.toArray, null)
        }
      },

      scalaJSStage := FastOptStage,

      // Close all test adapters every time a sequence of tasks ends
      onComplete := {
        val prev = onComplete.value

        { () =>
          prev()
          closeAllTestAdapters()
        }
      },
  )

  private def stageSettings(stage: Stage,
      key: TaskKey[Attributed[File]]): Seq[Setting[_]] = Def.settings(

      scalaJSLinker in key := {
        val scalaJSVersion = dynScalaJSVersion.value.get
        val classLoader = dynScalaJSClassLoader.value
        val config = (scalaJSLinkerConfig in key).value

        val oldLinkerAPI = hasOldLinkerAPI(scalaJSVersion)

        val stdLinkerMod = loadModule(classLoader,
            s"${linkerPackage(oldLinkerAPI)}.StandardLinker")
        invokeMethod(stdLinkerMod, "apply", config).asInstanceOf[AnyRef]
      },

      key := {
        val s = streams.value
        val scalaJSVersion = dynScalaJSVersion.value.get
        val classLoader = dynScalaJSClassLoader.value
        val linker = (scalaJSLinker in key).value
        val classpath = Attributed.data(fullClasspath.value)
        val output = (artifactPath in fastOptJS).value

        stage match {
          case Stage.FastOpt => s.log.info("Fast optimizing " + output)
          case Stage.FullOpt => s.log.info("Full optimizing " + output)
        }

        val oldLinkerAPI = hasOldLinkerAPI(scalaJSVersion)

        val irContainerModName = irioPackage(oldLinkerAPI) + {
          if (scalaJSVersion.startsWith("0.6.")) ".IRFileCache$IRContainer"
          else ".FileScalaJSIRContainer"
        }
        val irContainerMod = loadModule(classLoader, irContainerModName)
        val scalaJSClasspathSeq = seq2scalaJSSeq(classLoader, classpath)
        val irContainers = invokeMethod(irContainerMod, "fromClasspath",
            scalaJSClasspathSeq)

        val writableFileVirtualJSFileMod = loadModule(classLoader,
            s"${ioPackage(oldLinkerAPI)}.WritableFileVirtualJSFile")
        val outFile = invokeMethod(writableFileVirtualJSFileMod, "apply",
            output)

        val irFileCache = newInstance(classLoader,
            s"${irioPackage(oldLinkerAPI)}.IRFileCache")
        val cache = invokeMethod(irFileCache, "newCache").asInstanceOf[AnyRef]
        val irFiles = invokeMethod(cache, "cached", irContainers)

        val moduleInitializers = seq2scalaJSSeq(classLoader,
            scalaJSModuleInitializers.value)

        val logger = sbtLogger2scalaJSLogger(scalaJSVersion, classLoader, s.log)

        invokeMethod(linker, "link", irFiles, moduleInitializers, outFile, logger)

        Attributed.blank(output)
      },
  )

  val configSettings: Seq[Setting[_]] = Def.settings(
      unmanagedSourceDirectories += {
        val suffix = dynScalaJSVersion.value.fold("jvm")(_ => "js")
        val scalaSrcDir = scalaSource.value
        scalaSrcDir.getParentFile / s"${scalaSrcDir.getName}-$suffix"
      },

      stageSettings(FastOptStage, fastOptJS),
      stageSettings(FullOptStage, fullOptJS),

      Seq(fastOptJS, fullOptJS).map { key =>
        moduleName in key := {
          val configSuffix = configuration.value match {
            case Compile => ""
            case config  => "-" + config.name
          }
          moduleName.value + configSuffix
        }
      },

      scalaJSLinkerConfig in fullOptJS := {
        val prev = (scalaJSLinkerConfig in fullOptJS).value
        val prevSemantics = invokeMethod(prev, "semantics")
        val newSemantics = invokeMethod(prevSemantics, "optimized")
        val config1 = invokeMethod(prev, "withSemantics", newSemantics)
        val config2 = invokeMethod(config1, "withClosureCompilerIfAvailable", true)
        config2.asInstanceOf[AnyRef]
      },

      scalaJSMainModuleInitializer := {
        val scalaJSVersion = dynScalaJSVersion.value.get
        val classLoader = dynScalaJSClassLoader.value
        mainClass.value.map { mainCl =>
          val oldLinkerAPI = hasOldLinkerAPI(scalaJSVersion)
          val moduleInitializerMod = loadModule(classLoader,
              s"${linkerPackage(oldLinkerAPI)}.ModuleInitializer")
          invokeMethod(moduleInitializerMod, "mainMethodWithArgs",
              mainCl, "main")
        }
      },

      /* Do not inherit scalaJSModuleInitializers from the parent configuration.
       * Instead, always derive them straight from the Zero configuration
       * scope.
       */
      scalaJSModuleInitializers :=
        (scalaJSModuleInitializers in (This, Zero, This)).value,

      scalaJSModuleInitializers ++= {
        if (scalaJSUseMainModuleInitializer.value) {
          Seq(scalaJSMainModuleInitializer.value.getOrElse {
            throw new MessageOnlyException(
                "No main module initializer was specified (possibly because " +
                "no or multiple main classes were found), but " +
                "scalaJSUseMainModuleInitializer was set to true. " +
                "You can explicitly specify it either with " +
                "`mainClass := Some(...)` or with " +
                "`scalaJSMainModuleInitializer := Some(...)`")
          })
        } else {
          Seq.empty
        }
      },

      artifactPath in fastOptJS := {
        ((crossTarget in fastOptJS).value /
            ((moduleName in fastOptJS).value + "-fastopt.js"))
      },

      artifactPath in fullOptJS := {
        ((crossTarget in fullOptJS).value /
            ((moduleName in fullOptJS).value + "-opt.js"))
      },

      scalaJSLinkedFile := Def.settingDyn {
        scalaJSStage.value match {
          case Stage.FastOpt => fastOptJS
          case Stage.FullOpt => fullOptJS
        }
      }.value,

      /* Do not inherit jsExecutionFiles from the parent configuration.
       * Instead, always derive them straight from the Zero configuration
       * scope.
       */
      jsExecutionFiles := (jsExecutionFiles in (This, Zero, This)).value,

      // Crucially, add the Scala.js linked file to the JS files
      jsExecutionFiles +=  new FileVirtualJSFile(scalaJSLinkedFile.value.data),

      run := Def.settingDyn[InputTask[Unit]] {
        dynScalaJSVersion.value match {
          case None =>
            Defaults.foregroundRunTask

          case Some(scalaJSVersion) =>
            Def.inputTask {
              if (!scalaJSUseMainModuleInitializer.value) {
                throw new MessageOnlyException(
                    "`run` is only supported with " +
                    "scalaJSUseMainModuleInitializer := true")
              }

              val log = streams.value.log
              val env = jsEnv.value
              val files = jsExecutionFiles.value

              val scalaJSLogger = Loggers.sbtLogger2ToolsLogger(log)

              log.info("Running " + mainClass.value.getOrElse("<unknown class>"))
              log.debug(s"with JSEnv ${env.name}")

              val jsRunner = env.jsRunner(files)
              jsRunner.run(scalaJSLogger, ConsoleJSConsole)
            }
        }
      }.evaluated,
  )

  val compileConfigSettings: Seq[Setting[_]] = configSettings

  val testConfigSettings: Seq[Setting[_]] = Def.settings(
      configSettings,

      /* Always default to false for scalaJSUseMainModuleInitializer in testing
       * configurations, even if it is true in the Global configuration scope.
       */
      scalaJSUseMainModuleInitializer := false,

      loadedTestFrameworks := Def.settingDyn[Task[Map[TestFramework, sbt.testing.Framework]]] {
        dynScalaJSVersion.value match {
          case None =>
            Def.task {
              val loader = testLoader.value
              val log = streams.value.log
              testFrameworks.value.flatMap { f =>
                f.create(loader, log).map(x => (f, x)).toIterable
              }.toMap
            }

          case Some(scalaJSVersion) =>
            Def.task {
              if (fork.value) {
                throw new MessageOnlyException(
                    "`test` tasks in a Scala.js project require " +
                    "`fork in Test := false`.")
              }

              val zeroSixVersion = raw"""0\.6\.(\d+)""".r
              val knownInvalid = scalaJSVersion match {
                case "1.0.0-M1"        => true
                case zeroSixVersion(v) => v.toInt < 22
                case _                 => false
              }
              if (knownInvalid) {
                throw new MessageOnlyException(
                    "Your version of sbt-dynscalajs is known not to be able " +
                    s"to run tests with Scala.js $scalaJSVersion")
              }

              val s = streams.value

              val frameworks = testFrameworks.value

              val env = jsEnv.value match {
                case env: ComJSEnv =>
                  env
                case env =>
                  throw new MessageOnlyException(
                      s"You need a ComJSEnv to test (found ${env.name})")
              }

              val files = jsExecutionFiles.value

              // TODO Fetch this from scalaJSLinkerConfig
              val moduleKind = ModuleKind.NoModule
              val moduleIdentifier = None

              val frameworkNames = frameworks.map(_.implClassNames.toList).toList

              val logger = Loggers.sbtLogger2ToolsLogger(streams.value.log)
              val config = TestAdapter.Config()
                .withLogger(logger)
                .withModuleSettings(moduleKind, moduleIdentifier)

              val adapter = newTestAdapter(env, files, config)
              val frameworkAdapters = adapter.loadFrameworks(frameworkNames)

              frameworks.zip(frameworkAdapters).collect {
                case (tf, Some(adapter)) => (tf, adapter)
              }.toMap
            }
        }
      }.value,
  )

  val baseProjectSettings: Seq[Setting[_]] = Seq(
      platformDepsCrossVersion := {
        dynScalaJSVersion.value match {
          case None =>
            platformDepsCrossVersion.value
          case Some(v) =>
            ScalaJSCrossVersion.binary(
                ScalaJSCrossVersion.binaryScalaJSVersion(v))
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
      },

      scalaJSLinkerConfig := {
        val scalaJSVersion = dynScalaJSVersion.value.get
        val classLoader = dynScalaJSClassLoader.value

        val oldLinkerAPI = hasOldLinkerAPI(scalaJSVersion)
        val configMod = loadModule(classLoader,
            s"${linkerPackage(oldLinkerAPI)}.StandardLinker$$Config")
        invokeMethod(configMod, "apply").asInstanceOf[AnyRef]
      },

      scalaJSModuleInitializers := Seq(),
      scalaJSUseMainModuleInitializer := false,

      jsEnv := new NodeJSEnv(),

      jsExecutionFiles := Nil,
  )

  override def projectSettings: Seq[Setting[_]] = {
    baseProjectSettings ++
    inConfig(Compile)(compileConfigSettings) ++
    inConfig(Test)(testConfigSettings)
  }
}
