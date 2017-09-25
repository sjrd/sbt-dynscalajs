scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked",
  "-feature",
  "-encoding",
  "utf8"
)

addSbtPlugin("org.scala-native" % "sbt-crossproject" % "0.2.2")
libraryDependencies ++= Seq(
  "org.scala-js" %% "scalajs-sbt-test-adapter" % "1.0.0-M1",
  "org.scala-js" %% "scalajs-env-nodejs" % "1.0.0-M1",
)

unmanagedSourceDirectories in Compile +=
  baseDirectory.value.getParentFile / "sbt-dynscalajs/src/main/scala"
