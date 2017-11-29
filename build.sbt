inThisBuild(Seq(
  version := "0.2.0-SNAPSHOT",
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
      "org.scala-js" %% "scalajs-sbt-test-adapter" % "1.0.0-M1",
      "org.scala-js" %% "scalajs-env-nodejs" % "1.0.0-M1",
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
    libraryDependencies += "com.lihaoyi" %%% "utest" % "0.5.3" % "test",
    testFrameworks += new TestFramework("utest.runner.Framework")
  )
