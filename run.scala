#!/usr/bin/env scalas

/***
scalaVersion := "2.12.3"
scalacOptions += "-deprecation"
libraryDependencies += "org.jfree" % "jfreechart" % "1.0.19"
libraryDependencies += "net.java.dev.jna" % "jna-platform" % "4.5.0"
libraryDependencies += "com.github.scopt" %% "scopt" % "3.7.0"
*/

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.Pointer
import java.io.File
import java.util.ArrayList
import org.jfree.chart._
import org.jfree.chart.axis._
import org.jfree.chart.labels._
import org.jfree.chart.plot._
import org.jfree.chart.renderer.category._
import org.jfree.data.statistics._
import scala.sys.process._
import scala.collection.JavaConverters._

val IsWindows = sys.props("os.name").startsWith("Windows");
val ShellPrefix = if (IsWindows) "cmd /C " else ""

case class Cmd(cmd: String, title: String, dir: File)

val LangCmds = Map(
  "go" -> Cmd("go run main.go", "Go", new File("go")),
  "rust" -> Cmd("cargo run --release", "Rust/hyper", new File("rust")),
  "scala" -> Cmd(s"${ShellPrefix}sbt run", "Scala/Akka", new File("scala")),
  "nodejs" -> Cmd("node main.js", "Node.js", new File("nodejs")),
  "ldc2" -> Cmd(
    "dub run --compiler=ldc2 --build=release --force", "D (LDC/vibe.d)", new File("d")),
  "dmd" -> Cmd(
    "dub run --compiler=dmd --build=release --force", "D (DMD/vibe.d)", new File("d"))
)

val GoPath = sys.env("GOPATH")
val LsofPattern = raw"""p(\d+)""".r
val NetstatPattern = raw"""\s+\w+\s+[\d\.]+:3000\s+[\d\.]+:\d+\s+\w+\s+(\d+)""".r
val CsvPattern = raw"""([\d\.]+),([\d\.]+),([\d\.]+),([\d\.]+),([\d\.]+),([\d\.]+)""".r
val DefaultImg = "result.png"

def print(msg: String): Unit = {
  println(msg)
}

def runHey(lang: String, isIndex: Boolean): List[Double] = {
  val url = "http://127.0.0.1:3000/" + (if (isIndex) "" else "greeting/hello")
  val suffix = if (isIndex) "index" else "regex"
  val cmd = s"$GoPath/bin/hey -n 50000 -c 256 -t 10"
  val csvCmd = s"$cmd -o csv $url"
  // First run, for JIT
  csvCmd ! ProcessLogger(_ => ())
  // Second run, for UI
  val runCmd = s"$cmd $url"
  print(s"[$lang] $url")
  runCmd.!
  // Third run, for stats
  val stream = csvCmd lineStream_! ProcessLogger(line => ())
  val values = stream.flatMap { (line) => line match {
      case CsvPattern(responseTime, dnsLookup, dns, requestWrite, responseDelay, responseRead) => {
        Some(responseTime.toDouble * 1000)
      }
      case _ => None
    }
  }
  values.toList
}

def calculateStats(lazyValues: List[Double]): BoxAndWhiskerItem = {
  // Lazy evaluation is too slow, need to materialize
  val values = new ArrayList(lazyValues.asJava)
  val item = BoxAndWhiskerCalculator.calculateBoxAndWhiskerStatistics(values)
  val mean = item.getMean()
  val median = item.getMedian()
  val q1 = item.getQ1()
  val q3 = item.getQ3()
  val minRegularValue = item.getMinRegularValue()
  val maxRegularValue = item.getMaxRegularValue()
  // ignore outliers
  new BoxAndWhiskerItem(mean, median, q1, q3, minRegularValue, maxRegularValue,
    null, null, null)
}

def kill(pid: String): Unit = {
  if (IsWindows) {
    Seq("taskkill", "/t","/f", "/pid", pid).!
  } else {
    Seq("pkill", "-KILL", "-P", pid).!
    // pkill doesn't always work
    Seq("kill", "-9", pid).!
  }
}

def killProcesses(): Unit = {
  if (IsWindows) {
    val netstat = Seq("netstat", "-ona")
    netstat.lineStream_!.foreach { (line) => line match {
        case NetstatPattern(pid) => kill(pid)
        case _ =>
      }
    }
  } else {
    val lsof = Seq("lsof", "-Fp", "-i", ":3000")
    lsof.lineStream_!.foreach { (line) => line match {
        case LsofPattern(pid) => kill(pid)
        case _ =>
      }
    }
  }
}

def getPrivateField(proc: Object, name: String): Long = {
  val pidField = proc.getClass.getDeclaredField(name)
  pidField.synchronized {
    pidField.setAccessible(true)
    try {
      pidField.getLong(proc)
    } finally {
      pidField.setAccessible(false)
    }
  }
}

def pid(p: Process): Long = {
  val procField = p.getClass.getDeclaredField("p")
  procField.synchronized {
    procField.setAccessible(true)
    val proc = procField.get(p)
    try {
      proc match {
        case unixProc
          if unixProc.getClass.getName == "java.lang.UNIXProcess" => {
            getPrivateField(unixProc, "pid")
          }
        case windowsProc
          if windowsProc.getClass.getName == "java.lang.ProcessImpl" => {
            val processHandle = getPrivateField(windowsProc, "handle")
            val kernel = Kernel32.INSTANCE
            val winHandle = new HANDLE()
            winHandle.setPointer(Pointer.createConstant(processHandle))
            kernel.GetProcessId(winHandle)
          }
        case _ => throw new RuntimeException(
          "Cannot get PID of a " + proc.getClass.getName)
      }
    } finally {
      procField.setAccessible(false)
    }
  }
}

def run(langs: Seq[String], verbose: Boolean): BoxAndWhiskerCategoryDataset = {
  val dataset = new DefaultBoxAndWhiskerCategoryDataset()

  for (lang <- langs) {
    killProcesses()

    val langCmd = LangCmds(lang)
    val proc = Process(langCmd.cmd, langCmd.dir).run
    Thread.sleep(10000)

    val indexValues = runHey(lang, true)
    val langTitle = lang.capitalize
    dataset.add(
      calculateStats(indexValues), "Index URL Request", langCmd.title)
    val patternValues = runHey(lang, false)
    dataset.add(
      calculateStats(patternValues), "Pattern URL Request", langCmd.title)

    val processId = pid(proc).toString
    if (verbose) {
      print(s"Killing $processId process tree...")
    }
    kill(processId)
  }

  dataset
}

def writeStats(dataset: BoxAndWhiskerCategoryDataset, out: File): Unit = {
  val xAxis = new CategoryAxis("Language")
  val yAxis = new NumberAxis("Response, ms")
  yAxis.setAutoRangeIncludesZero(false)
  val renderer = new BoxAndWhiskerRenderer()
  renderer.setFillBox(false)
  renderer.setMeanVisible(false)
  renderer.setWhiskerWidth(0.5)
  val plot = new CategoryPlot(dataset, xAxis, yAxis, renderer)

  val chart = new JFreeChart(plot)
  ChartUtilities.saveChartAsPNG(out, chart, 700, 350);
}

case class Config(
  out: File = new File(DefaultImg),
  verbose: Boolean = false,
  langs: Seq[String] = Seq())

val parser = new scopt.OptionParser[Config]("run.scala") {
  opt[File]('o', "out").optional().valueName("<file>").
    action( (x, c) => c.copy(out = x) ).
    text(s"image file to generate ($DefaultImg by default)")

  opt[Unit]("verbose").action( (_, c) =>
    c.copy(verbose = true) ).text("verbose execution output")

  arg[String]("<lang>...").unbounded().required().action( (x, c) =>
    c.copy(langs = c.langs :+ x) ).text("languages to test ('all' for all)")

  note(s"""
The following languages are supported: ${ LangCmds.keys.mkString(", ") }.""")
}

def entryPoint(args: Array[String]): Unit = {
  parser.parse(args, Config()) match {
    case Some(config) => {
      var list = config.langs.map(_ match {
        case "all" => LangCmds.keys
        case x: String => List(x)
      }).flatten.filter(LangCmds.contains)
      print("Run tests for: " + list.mkString(" "))
      val ds = run(list, config.verbose)
      writeStats(ds, config.out)
    }
    case None =>
    // arguments are bad, error message will have been displayed
  }
}

entryPoint(args)
