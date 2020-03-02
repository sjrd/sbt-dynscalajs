val ReferenceScalaJSVersion = "1.0.0"
val ReferenceScalaJS06xVersion = "0.6.28"

inThisBuild(Seq(
  version := "1.0.1-SNAPSHOT",
  organization := "be.doeraene",

  crossScalaVersions := Seq("2.12.10", "2.11.12", "2.13.1"),
  scalaVersion := crossScalaVersions.value.head,
  scalacOptions ++= Seq("-deprecation", "-feature", "-Xfatal-warnings"),

  homepage := Some(url("https://github.com/sjrd/sbt-dynscalajs")),
  licenses += ("BSD New",
      url("https://github.com/sjrd/sbt-dynscalajs/blob/master/LICENSE")),
  scmInfo := Some(ScmInfo(
      url("https://github.com/sjrd/sbt-dynscalajs"),
      "scm:git:git@github.com:sjrd/sbt-dynscalajs.git",
      Some("scm:git:git@github.com:sjrd/sbt-dynscalajs.git"))),

  publishMavenStyle := true,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (version.value.endsWith("-SNAPSHOT"))
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  pomExtra := (
    <developers>
      <developer>
        <id>sjrd</id>
        <name>Sébastien Doeraene</name>
        <url>https://github.com/sjrd/</url>
      </developer>
    </developers>
  ),
  pomIncludeRepository := { _ => false }
))

val SourceDeps = config("sourcedeps")

val internalTestBridgeDep =
  taskKey[Classpath]("optional internal dependency on the test bridge")

lazy val root = project.in(file(".")).
  settings(
    publishArtifact in Compile := false,
    publish := {},
    publishLocal := {},

    clean := clean.dependsOn(
      clean in `sbt-dynscalajs`,
      clean in `sbt-dynscalajs-test-bridge`,
      clean in `sbt-dynscalajs-test-project`
    ).value
  )

lazy val `sbt-dynscalajs` = project.in(file("sbt-dynscalajs")).
  settings(
    sbtPlugin := true,
    addSbtPlugin("org.portable-scala" % "sbt-platform-deps" % "1.0.0"),
    libraryDependencies ++= Seq(
      "org.scala-js" %% "scalajs-sbt-test-adapter" % ReferenceScalaJSVersion,
      "org.scala-js" %% "scalajs-env-nodejs" % ReferenceScalaJSVersion,
    )
  )

/** A project to recompile the sources of the 1.x test-bridge for 0.6.x. */
lazy val `sbt-dynscalajs-test-bridge`: Project = project.
  in(file("test-bridge")).
  enablePlugins(DynScalaJSPlugin).
  settings(
    dynScalaJSVersion := Some(ReferenceScalaJS06xVersion),

    libraryDependencies ~= { _.filterNot(_.name.startsWith("sbt-dynscalajs-test-bridge")) },

    libraryDependencies +=
      "org.scala-js" %% "scalajs-test-interface" % ReferenceScalaJS06xVersion,

    /* Add the sources of scalajs-test-bridge 1.x, which we will recompile for
     * Scala.js 0.6.X.
     */
    ivyConfigurations += SourceDeps.hide,
    transitiveClassifiers := Seq("sources"),
    libraryDependencies +=
      ("org.scala-js" %% "scalajs-test-bridge" % ReferenceScalaJSVersion % "sourcedeps"),
    sourceGenerators in Compile += Def.task {
      val s = streams.value
      val cacheDir = s.cacheDirectory
      val trgDir = (sourceManaged in Compile).value / "scalajs-test-bridge-src"

      val report = updateClassifiers.value
      val scalaJSTestBridgeSourcesJar = report.select(
          configuration = configurationFilter("sourcedeps"),
          module = (_: ModuleID).name.startsWith("scalajs-test-bridge_"),
          artifact = artifactFilter(`type` = "src")).headOption.getOrElse {
        sys.error(s"Could not fetch scalajs-test-bridge sources")
      }

      FileFunction.cached(cacheDir / s"fetchScalaJSTestBridgeSource",
          FilesInfo.lastModified, FilesInfo.exists) { dependencies =>
        s.log.info(s"Unpacking scalajs-test-bridge sources to $trgDir...")
        if (trgDir.exists)
          IO.delete(trgDir)
        IO.createDirectory(trgDir)
        IO.unzip(scalaJSTestBridgeSourcesJar, trgDir)
        (trgDir ** "*.scala").get.toSet
      } (Set(scalaJSTestBridgeSourcesJar)).toSeq
    }.taskValue,
  )

lazy val `sbt-dynscalajs-test-project`: Project = project.
  in(file("test-project")).
  enablePlugins(DynScalaJSPlugin).
  settings(
    scalaJSUseMainModuleInitializer := true,

    libraryDependencies += {
      dynScalaJSVersion.value match {
        case None =>
          "com.novocode" % "junit-interface" % "0.11" % "test"
        case Some(scalaJSVer) =>
          "org.scala-js" %% "scalajs-junit-test-runtime" % scalaJSVer % "test"
      }
    },

    libraryDependencies ~= { _.filterNot(_.name.startsWith("sbt-dynscalajs-test-bridge")) },

    internalTestBridgeDep := Def.settingDyn {
      if (dynScalaJSVersion.value.exists(_.startsWith("0.6."))) {
        Def.task[Classpath] {
          val prods = (products in (`sbt-dynscalajs-test-bridge`, Compile)).value
          prods.map(Attributed.blank(_))
        }
      } else {
        Def.task[Classpath](Nil)
      }
    }.value,

    internalDependencyClasspath in Test ++= internalTestBridgeDep.value,

    scalacOptions in Test ++= {
      val scalaVer = scalaVersion.value
      val s = streams.value
      val log = s.log
      val retrieveDir = s.cacheDirectory / "scalajs-junit-plugin"
      dynScalaJSVersion.value match {
        case None =>
          Nil
        case Some(scalaJSVer) =>
          val lm = {
            import sbt.librarymanagement.ivy._
            val ivyConfig = InlineIvyConfiguration().withLog(log)
            IvyDependencyResolution(ivyConfig)
          }
          val moduleID =
            "org.scala-js" % s"scalajs-junit-test-plugin_$scalaVer" % scalaJSVer
          val maybeFiles =
            lm.retrieve(moduleID, scalaModuleInfo = None, retrieveDir, log)
          val files = maybeFiles.fold({ unresolvedWarn =>
            throw unresolvedWarn.resolveException
          }, { files =>
            files
          })
          val jar = files.find(_.getName.startsWith("scalajs-junit-test-plugin_")).getOrElse {
            throw new MessageOnlyException("Could not find scalajs-junit-test-plugin")
          }
          Seq("-Xplugin:" + jar.getAbsolutePath)
      }
    },
  )
