package com.fongmun.testjs

import java.io.File
import java.util

import org.openqa.selenium.phantomjs.{PhantomJSDriver, PhantomJSDriverService}
import org.openqa.selenium.remote.DesiredCapabilities
import sbt.Keys._
import sbt._

import scala.collection.JavaConverters._

object TestJsPlugin extends AutoPlugin {
  object autoImport {
    case class TestJsSuite(
      libs: PathFinder,
      tests: PathFinder
    )

    case class TestJsSuiteResult(
      total: Long,
      success: Long,
      failure: Long
    )

    val testJs = TaskKey[Unit]("testJs", "Run tests with Jasmine")
    val testJsBuild = TaskKey[Seq[File]]("testJsBuild", "Prepare the test HTML files")

    val testJsSuites = SettingKey[Seq[TestJsSuite]]("testJsSuites", "One suite corresponds to one HTML file. Divide them wisely")

    val testJsOutputDir = SettingKey[File]("testJsOutputDir", "directory to output files to")
    val testJsPhantomJsBinPath = SettingKey[String]("testJsPhantomJsBinPath", "The full path of PhantomJS executable")
    val testJsPhantomJsDriver = SettingKey[() => PhantomJSDriver]("testJsPhantomJsDriver")
  }
  import autoImport._
  override def requires = empty
  override def trigger = allRequirements
  override lazy val projectSettings = Seq(
    testJsOutputDir := (target in test).value / "testjs",
    testJsSuites := Seq.empty,
    testJsPhantomJsBinPath := "/usr/local/bin/phantomjs",
    testJsPhantomJsDriver := { () =>
      val capabilities = {
        val caps = new DesiredCapabilities()
        caps.setJavascriptEnabled(true)
        caps.setCapability("takesScreenshot", true)
        caps.setCapability(
          PhantomJSDriverService.PHANTOMJS_EXECUTABLE_PATH_PROPERTY,
          testJsPhantomJsBinPath.value
        )
        caps.setCapability(
          PhantomJSDriverService.PHANTOMJS_CLI_ARGS,
          Array[String](
            "--load-images=false",
            "--ignore-ssl-errors=yes",
            "--proxy-type=none",
            "--local-to-remote-url-access=true",
            "--ssl-protocol=TLSv1",
            "--disk-cache=true",
            "--max-disk-cache-size=200000",
            "--web-security=false"
          )
        )
        caps
      }
      new PhantomJSDriver(
      {
        new PhantomJSDriverService.Builder()
          .usingPhantomJSExecutable(new File(testJsPhantomJsBinPath.value))
          .usingGhostDriver(null)
          .usingAnyFreePort
          .withProxy(null)
          .withLogFile(null)
          .usingCommandLineArguments(
            capabilities.getCapability(PhantomJSDriverService.PHANTOMJS_CLI_ARGS).asInstanceOf[Array[String]]
          )
          .usingGhostDriverCommandLineArguments(
            capabilities.getCapability(PhantomJSDriverService.PHANTOMJS_GHOSTDRIVER_CLI_ARGS).asInstanceOf[Array[String]]
          )
          .build()
      },
      capabilities
      )
    },
    testJsBuild := {
      val logger = sLog.value
      val jasmineDir = testJsOutputDir.value / "jasmine"
      val jasmineDirAbsolutePath = jasmineDir.getAbsolutePath
      extractJasmineFiles(jasmineDir)

      testJsSuites.value.zipWithIndex.map { case (suite, index) =>
        val testHtmlAbsolutePath = (testJsOutputDir.value / s"testjs_$index.html").getAbsolutePath
        generateHtml(index, suite, jasmineDirAbsolutePath, testHtmlAbsolutePath, logger)
      }
    },
    testJs := {
      val files = testJsBuild.value
      val logger = sLog.value
      val browser = testJsPhantomJsDriver.value()

      try {
        val summary = files.foldLeft(TestJsSuiteResult(0, 0, 0)) { case (soFar, file) =>
          val result = execute(browser, logger, file)

          soFar.copy(
            total = soFar.total + result.total,
            success = soFar.success + result.success,
            failure = soFar.failure + result.failure
          )
        }

        val summaryText = s"Total: ${summary.total}, Success: ${summary.success}, Failure: ${summary.failure}"

        if (summary.failure > 0) {
          sys.error(summaryText)
        } else {
          logger.info(summaryText)
        }
      } finally {
        browser.close()
      }
    }
  )

  // Fix up an absolute path so it can be concatenated onto "file://" to get a URI. On Unix, absolute paths
  // will start with '/' so need no alteration. On Windows, a path will be something like C:\SomeDirectory\file.txt
  // which needs to transform to file:///C:/SomeDirectory/file.txt
  // See https://blogs.msdn.microsoft.com/ie/2006/12/06/file-uris-in-windows/
  // This is a simple implementation; I'm only doing percent-encoding of spaces.
  def fixUpPath(path: String): String = if (path.startsWith("/")) path else "/" + path.replace('\\', '/').replace(" ", "%20")

  def generateHtml(
    index: Int,
    suite: TestJsSuite,
    jasmineDirAbsolutePath: String,
    testHtmlAbsolutePath: String,
    logger: Logger
  ): File = {
    val allLibJsTags = generateTagsHtml(suite.libs)
    val allTestJsTags = generateTagsHtml(suite.tests)

    val fixedPath = fixUpPath(jasmineDirAbsolutePath)

    IO.write(
      new File(testHtmlAbsolutePath),
      s"""
          | <html>
          |   <head>
          |     <link rel="shortcut icon" type="image/png" href="file://$fixedPath/jasmine_favicon.png">
          |     <link rel="stylesheet" type="text/css" href="file://$fixedPath/jasmine.css">
          |
          |     <script type="text/javascript" src="file://$fixedPath/override.js"></script>
          |
          |     <script type="text/javascript" src="file://$fixedPath/jasmine.js"></script>
          |     <script type="text/javascript" src="file://$fixedPath/jasmine-html.js"></script>
          |     <script type="text/javascript" src="file://$fixedPath/boot.js"></script>
          |     <script type="text/javascript">
          |       jasmine.getEnv().addReporter(reporter);
          |     </script>
          |     $allLibJsTags
          |     $allTestJsTags
          |   </head>
          |   <body>
          |   </body>
          | </html>
      """.stripMargin
    )

    logger.info(s"Build $testHtmlAbsolutePath for [${suite.tests.get.map(_.getName).mkString(", ")}]")

    new File(testHtmlAbsolutePath)
  }

  def execute(
    browser: PhantomJSDriver,
    logger: Logger,
    testHtml: File
  ): TestJsSuiteResult = {
    val fixedExecutePath = fixUpPath(testHtml.getAbsolutePath)
    logger.info(s"Execute $fixedExecutePath")
    browser.get(s"file://$fixedExecutePath")
    browser.executeScript("return consoleOutputs;").asInstanceOf[util.ArrayList[String]].asScala.foreach { line =>
      logger.info(line)
    }

    TestJsSuiteResult(
      total = browser.executeScript("return summary.total;").asInstanceOf[Long],
      success = browser.executeScript("return summary.success;").asInstanceOf[Long],
      failure =browser.executeScript("return summary.failure;").asInstanceOf[Long]
    )
  }

  def generateTagsHtml(path: PathFinder): String = {
    path.get
      .map(_.getAbsolutePath)
      .map { path =>
        s"""<script type="text/javascript" src="file://${fixUpPath(path)}"></script>"""
      }
      .mkString("\n")
  }

  def extractJasmineFiles(parentDir: File): Unit = {
    List(
      "jasmine_favicon.png",
      "jasmine.css",
      "override.js",
      "jasmine.js",
      "jasmine-html.js",
      "boot.js"
    ).foreach { file =>
      transfer(s"jasmine/$file", parentDir / file)
    }
  }

  def transfer(resourcePath: String, dest: File): Unit = {
    val inputStream = this.getClass.getClassLoader.getResourceAsStream(resourcePath)

    IO.transfer(inputStream, dest)
    inputStream.close()
  }
}