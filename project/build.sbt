scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked",
  "-feature",
  "-encoding",
  "utf8"
)

addSbtPlugin("org.portable-scala" % "sbt-platform-deps" % "1.0.0")
libraryDependencies ++= Seq(
  "org.scala-js" %% "scalajs-sbt-test-adapter" % "1.0.0",
  "org.scala-js" %% "scalajs-env-nodejs" % "1.0.0",
)

unmanagedSourceDirectories in Compile +=
  baseDirectory.value.getParentFile / "sbt-dynscalajs/src/main/scala"
