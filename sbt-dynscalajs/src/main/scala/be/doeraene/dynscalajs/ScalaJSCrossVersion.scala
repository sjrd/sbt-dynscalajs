package be.doeraene.sbtdynscalajs

import sbt._

object ScalaJSCrossVersion {
  private final val ReleaseVersion =
    raw"""(\d+)\.(\d+)\.(\d+)""".r
  private final val MinorSnapshotVersion =
    raw"""(\d+)\.(\d+)\.([1-9]\d*)-SNAPSHOT""".r

  def binaryScalaJSVersion(full: String): String = full match {
    case ReleaseVersion("0", minor, _)       => "0." + minor
    case ReleaseVersion(major, _, _)         => major
    case MinorSnapshotVersion("0", minor, _) => "0." + minor
    case MinorSnapshotVersion(major, _, _)   => major
    case _                                   => full
  }

  def scalaJSMapped(cross: CrossVersion, scalaJSBinaryVersion: String): CrossVersion =
    crossVersionAddScalaJSPart(cross, "sjs" + scalaJSBinaryVersion)

  def binary(scalaJSBinaryVersion: String): CrossVersion =
    scalaJSMapped(CrossVersion.binary, scalaJSBinaryVersion)

  def full(scalaJSBinaryVersion: String): CrossVersion =
    scalaJSMapped(CrossVersion.full, scalaJSBinaryVersion)

  private def crossVersionAddScalaJSPart(cross: CrossVersion,
      part: String): CrossVersion = {
    cross match {
      case CrossVersion.Disabled =>
        CrossVersion.constant(part)
      case cross: sbt.librarymanagement.Constant =>
        cross.withValue(part + "_" + cross.value)
      case cross: CrossVersion.Binary =>
        cross.withPrefix(part + "_" + cross.prefix)
      case cross: CrossVersion.Full =>
        cross.withPrefix(part + "_" + cross.prefix)
    }
  }
}
