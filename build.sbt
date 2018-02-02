inThisBuild(Seq(
  version := "0.2.1",
  organization := "be.doeraene",

  scalaVersion := "2.12.4",
  scalacOptions ++= Seq("-deprecation", "-feature", "-Xfatal-warnings"),

  homepage := Some(url("https://github.com/sjrd/sbt-dynscalajs")),
  licenses += ("BSD New",
      url("https://github.com/sjrd/sbt-dynscalajs/blob/master/LICENSE")),
  scmInfo := Some(ScmInfo(
      url("https://github.com/sjrd/sbt-dynscalajs"),
      "scm:git:git@github.com:sjrd/sbt-dynscalajs.git",
      Some("scm:git:git@github.com:sjrd/sbt-dynscalajs.git")))
))

lazy val root = project.in(file(".")).
  settings(
    publishArtifact in Compile := false,
    publish := {},
    publishLocal := {},

    clean := clean.dependsOn(
      clean in `sbt-dynscalajs`,
      clean in `sbt-dynscalajs-test-project`
    ).value
  )

lazy val `sbt-dynscalajs` = project.in(file("sbt-dynscalajs")).
  settings(
    sbtPlugin := true,
    addSbtPlugin("org.portable-scala" % "sbt-platform-deps" % "1.0.0-M2"),
    libraryDependencies ++= Seq(
      "org.scala-js" %% "scalajs-sbt-test-adapter" % "1.0.0-M3",
      "org.scala-js" %% "scalajs-env-nodejs" % "1.0.0-M3",
    ),

    publishMavenStyle := true,
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
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
