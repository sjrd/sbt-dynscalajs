package be.doeraene.sbtdynscalajs

sealed trait Stage

object Stage {
  case object FullOpt extends Stage
  case object FastOpt extends Stage
}
