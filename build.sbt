import sbt.Keys._

name in ThisBuild := "scala-ts"

organization in ThisBuild := "org.scala-ts"

val commonSettings = Scalac.settings ++ Publish.settings

lazy val core = project.in(file("core")).settings(Seq(
  name := "scala-ts-core",
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-reflect" % scalaVersion.value,
    "org.scala-lang" % "scala-compiler" % scalaVersion.value,
    "ch.qos.logback" % "logback-classic" % "1.1.7",
    "org.scalatest" %% "scalatest" % "3.0.1" % Test
  ),
  mainClass in (Compile, run) := Some("org.scalats.Main")
) ++ commonSettings)

lazy val `sbt-plugin` = project.in(file("sbt-plugin")).
  settings(Seq(
    name := "scala-ts-sbt",
    sbtPlugin := true,
    sbtVersion in pluginCrossBuild := {
      scalaBinaryVersion.value match {
        case "2.10" => "0.13.17"
        case "2.12" => "1.1.0"
      }
    }) ++ commonSettings).dependsOn(core)

lazy val root = (project in file("."))
  .aggregate(core, `sbt-plugin`)
  .settings(commonSettings)
