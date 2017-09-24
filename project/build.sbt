scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked",
  "-feature",
  "-encoding",
  "utf8"
)

addSbtPlugin("org.scala-native" % "sbt-crossproject" % "0.2.2")

unmanagedSourceDirectories in Compile +=
  baseDirectory.value.getParentFile / "sbt-dynscalajs/src/main/scala"
