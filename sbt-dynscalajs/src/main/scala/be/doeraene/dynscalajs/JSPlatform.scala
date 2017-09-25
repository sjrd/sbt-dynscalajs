package be.doeraene.sbtdynscalajs

import sbt._

import sbtcrossproject._

final case class JSPlatform(scalaJSVersion: String,
    scalaJSBinaryVersion: String) extends Platform {

  val crossBinary: CrossVersion = ScalaJSCrossVersion.binary(scalaJSBinaryVersion)
  val crossFull: CrossVersion = ScalaJSCrossVersion.full(scalaJSBinaryVersion)

  def identifier: String = "js"
  def sbtSuffix: String = "JS"

  def enable(project: Project): Project = {
    project.enablePlugins(DynScalaJSPlugin).settings(
        DynScalaJSPlugin.autoImport.dynScalaJSVersion := Some(scalaJSVersion)
    )
  }
}

object JSPlatform {
  def apply(scalaJSVersion: String): JSPlatform = {
    JSPlatform(scalaJSVersion,
        ScalaJSCrossVersion.binaryScalaJSVersion(scalaJSVersion))
  }
}
