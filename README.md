# sbt-dynscalajs

[![Build Status](https://travis-ci.org/sjrd/sbt-dynscalajs.svg?branch=master)](https://travis-ci.org/sjrd/sbt-dynscalajs)

Dynamically switch between Scala.js versions (and JVM/JS) in an sbt build.

## Do *not* use this project if

### You want something supported

sbt-dynscalajs was written as a proof of concept.
The author does not intend to improve it nor to fix bugs.
Therefore, you should expect to fix things yourself.

The author only intends to update it to make it work with newer versions of Scala.js, should they break the proof of concept.

### You develop Scala.js-specific things

sbt-dynscalajs only has the *most basic features* necessary to build, run and test with Scala.js a project mainly intended for the JVM.
It does not have features targeted at Scala.js users.
Things that are absent include, but are not limited to:

* Support for a `shared` cross-compiling project used by a JVM server and a JS server
* `crossProject` (the plugin replaces that mechanism)
* `jsDependencies`, `testHtml`, `scalajsp`
* Any JS environment besides the default Node.js environment without DOM
* Any non-default configuration of the Scala.js linker
* Support for downstream sbt plugins extending Scala.js (e.g., `scalajs-bundler`, `sbt-jsdependencies`, `sbt-web-scalajs`)

## Do use this project if

All of the following apply:

* You are prepared to fix bugs and improve the plugin yourself
* You maintain a Scala library and are mostly interested in its JVM flavour
* You want to build, test and publish the Scala.js version of your library
* You do not like `crossProject`, and/or you do not like environment variables to switch between Scala.js 0.6.x and 1.x

## Usage

Not yet there, since there is no code yet in the repo.
