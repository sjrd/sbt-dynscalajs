# sbt-dynscalajs

[![Build Status](https://travis-ci.org/sjrd/sbt-dynscalajs.svg?branch=master)](https://travis-ci.org/sjrd/sbt-dynscalajs)

Dynamically switch between the JVM and JS targets in an sbt build, as well as between different versions of Scala.js.

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
* Support for downstream sbt plugins extending Scala.js (e.g., `scalajs-bundler`, `sbt-jsdependencies`, `sbt-web-scalajs`)
* Support for sbt 0.13.x

## Do use this project if

All of the following apply:

* You are prepared to fix bugs and improve the plugin yourself
* You maintain a Scala library and are mostly interested in its JVM flavour
* You want to build, test and publish the Scala.js version of your library
* You do not like `crossProject`, and/or you do not like environment variables to switch between Scala.js 0.6.x and 1.x
* Your project uses sbt 1.x

## Setup

Add the following dependency in your `project/plugins.sbt` file.

```scala
addSbtPlugin("be.doeraene" % "sbt-dynscalajs" % "0.1.0")
```

You will also need to enable the `DynScalaJSPlugin` auto-plugin on `project`s you want to cross-compile, e.g.

```scala
lazy val foo = project.
  enablePlugins(DynScalaJSPlugin).
  ...
```

In the future, `DynScalaJSPlugin` may become auto-triggered so that this is not necessary, but we stay safe for now.

Finally:

* Make sure your project uses sbt 1.x.
* Dependencies on other Scala/Scala.js libraries should use `%%%` instead of `%%`, as if you were using [sbt-crossproject](https://github.com/scala-native/sbt-crossproject).
* If your project has a `main` method that you want to `run`, make sure to put `scalaJSUseMainModuleInitializer := true` in that project's settings.

### If you were using sbt-scalajs

You *must remove `sbt-scalajs`* from your project.
sbt-dynscalajs is not compatible with sbt-scalajs, as it completely redefines its API.
Having both on the classpath of your build will break everything.

All `crossProject`s should be replaced by simple `project`s enabling the `DynScalaJSPlugin` auto-plugin.

## Usage

Once everything is setup, you can start sbt.
By default, all your projects are configured for the JVM, as if you had done nothing.
All tasks will behave as usual for a JVM project.

To switch to Scala.js, enter:

    > set dynScalaJSVersion := Some("1.0.0-M1")

or another supported version of Scala.js.

Magic! All the projects with `enablePlugins(DynScalaJSPlugin)` have been turned into Scala.js projects, using the specified version of Scala.js.
You can now use the most common Scala.js tasks, such as `fastOptJS` and `fullOptJS` and `scalaJSStage`.
Moreover, `run` and `test` (and `testOnly`/`testQuick`) behave as on a Scala.js project.

## Supported versions of Scala.js

The current version of sbt-dynscalajs has been tested with:

* Scala.js 1.0.0-M1
* Scala.js 0.6.19
* Scala.js 0.6.20, but in that version `test` and friends will not work (they will spectacularly blow up)

In a future version, sbt-dynscalajs will be upgraded to support `test` on 0.6.20+ and 1.0.0-M2+ (but at that point `test` will stop working for earlier versions of Scala.js).
