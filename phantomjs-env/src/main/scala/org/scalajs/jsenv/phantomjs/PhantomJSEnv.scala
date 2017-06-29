/*                     __                                                   *\
**     ________ ___   / /  ___      __ ____  PhantomJS support for Scala.js **
**    / __/ __// _ | / /  / _ | __ / // __/  (c) 2013-2017, LAMP/EPFL       **
**  __\ \/ /__/ __ |/ /__/ __ |/_// /_\ \    https://www.scala-js.org/      **
** /____/\___/_/ |_/____/_/ | |__/ /____/                                   **
**                          |/____/                                         **
\*                                                                          */

package org.scalajs.jsenv.phantomjs

import scala.collection.immutable

import org.scalajs.jsenv._
import org.scalajs.jsenv.Utils.OptDeadline

import org.scalajs.core.ir.Utils.{escapeJS, fixFileURI}

import org.scalajs.core.tools.io._
import org.scalajs.core.tools.logging._

import java.io.{ Console => _, _ }
import java.net._

import scala.io.Source
import scala.collection.mutable
import scala.annotation.tailrec

import scala.concurrent.{ExecutionContext, TimeoutException, Future}
import scala.concurrent.duration.Duration

class PhantomJSEnv(config: PhantomJSEnv.Config)
    extends ExternalJSEnv with ComJSEnv {

  import PhantomJSEnv._

  def this() = this(PhantomJSEnv.Config())

  @deprecated("Use the overload with a PhantomJSEnv.Config.", "0.6.18")
  def this(
      @deprecatedName('phantomjsPath)
      executable: String = "phantomjs",
      @deprecatedName('addArgs)
      args: Seq[String] = Seq.empty,
      @deprecatedName('addEnv)
      env: Map[String, String] = Map.empty,
      autoExit: Boolean = true,
      jettyClassLoader: ClassLoader = null
  ) = {
    this(
        PhantomJSEnv.Config()
          .withExecutable(executable)
          .withArgs(args.toList)
          .withEnv(env)
          .withAutoExit(autoExit)
          .withJettyClassLoader(jettyClassLoader))
  }

  protected def vmName: String = "PhantomJS"

  protected val executable: String = config.executable

  override protected def args: immutable.Seq[String] = config.args

  override protected def env: Map[String, String] = config.env

  val autoExit: Boolean = config.autoExit

  override def jsRunner(files: Seq[VirtualJSFile]): JSRunner =
    new PhantomRunner(files)

  override def asyncRunner(files: Seq[VirtualJSFile]): AsyncJSRunner =
    new AsyncPhantomRunner(files)

  override def comRunner(files: Seq[VirtualJSFile]): ComJSRunner =
    new ComPhantomRunner(files)

  protected class PhantomRunner(files: Seq[VirtualJSFile])
      extends ExtRunner(files) with AbstractPhantomRunner

  protected class AsyncPhantomRunner(files: Seq[VirtualJSFile])
      extends AsyncExtRunner(files) with AbstractPhantomRunner

  protected class ComPhantomRunner(files: Seq[VirtualJSFile])
      extends AsyncPhantomRunner(files) with ComJSRunner {

    private var mgrIsRunning: Boolean = false

    private object websocketListener extends WebsocketListener { // scalastyle:ignore
      def onRunning(): Unit = ComPhantomRunner.this.synchronized {
        mgrIsRunning = true
        ComPhantomRunner.this.notifyAll()
      }

      def onOpen(): Unit = ComPhantomRunner.this.synchronized {
        ComPhantomRunner.this.notifyAll()
      }

      def onClose(): Unit = ComPhantomRunner.this.synchronized {
        ComPhantomRunner.this.notifyAll()
      }

      def onMessage(msg: String): Unit = ComPhantomRunner.this.synchronized {
        recvBuf.enqueue(msg)
        ComPhantomRunner.this.notifyAll()
      }

      def log(msg: String): Unit = logger.debug(s"PhantomJS WS Jetty: $msg")
    }

    private def loadMgr() = {
      val loader =
        if (config.jettyClassLoader != null) config.jettyClassLoader
        else getClass().getClassLoader()

      val clazz = loader.loadClass(
          "org.scalajs.jsenv.phantomjs.JettyWebsocketManager")

      val ctors = clazz.getConstructors()
      assert(ctors.length == 1, "JettyWebsocketManager may only have one ctor")

      val mgr = ctors.head.newInstance(websocketListener)

      mgr.asInstanceOf[WebsocketManager]
    }

    private val mgr: WebsocketManager = loadMgr()

    future.onComplete(_ => synchronized(notifyAll()))(ExecutionContext.global)

    private[this] val recvBuf = mutable.Queue.empty[String]
    private[this] val fragmentsBuf = new StringBuilder

    private def comSetup = {
      def maybeExit(code: Int) =
        if (autoExit)
          s"window.callPhantom({ action: 'exit', returnValue: $code });"
        else
          ""

      /* The WebSocket server starts asynchronously. We must wait for it to
       * be fully operational before a) retrieving the port it is running on
       * and b) feeding the connecting JS script to the VM.
       */
      synchronized {
        while (!mgrIsRunning)
          wait(10000)
        if (!mgrIsRunning)
          throw new TimeoutException(
              "The PhantomJS WebSocket server startup timed out")
      }

      val serverPort = mgr.localPort
      assert(serverPort > 0,
          s"Manager running with a non-positive port number: $serverPort")

      val code = s"""
        |(function() {
        |  var MaxPayloadSize = $MaxCharPayloadSize;
        |
        |  // The socket for communication
        |  var websocket = null;
        |
        |  // Buffer for messages sent before socket is open
        |  var outMsgBuf = null;
        |
        |  function sendImpl(msg) {
        |    var frags = (msg.length / MaxPayloadSize) | 0;
        |
        |    for (var i = 0; i < frags; ++i) {
        |      var payload = msg.substring(
        |          i * MaxPayloadSize, (i + 1) * MaxPayloadSize);
        |      websocket.send("1" + payload);
        |    }
        |
        |    websocket.send("0" + msg.substring(frags * MaxPayloadSize));
        |  }
        |
        |  function recvImpl(recvCB) {
        |    var recvBuf = "";
        |
        |    return function(evt) {
        |      var newData = recvBuf + evt.data.substring(1);
        |      if (evt.data.charAt(0) == "0") {
        |        recvBuf = "";
        |        recvCB(newData);
        |      } else if (evt.data.charAt(0) == "1") {
        |        recvBuf = newData;
        |      } else {
        |        throw new Error("Bad fragmentation flag in " + evt.data);
        |      }
        |    };
        |  }
        |
        |  window.scalajsCom = {
        |    init: function(recvCB) {
        |      if (websocket !== null) throw new Error("Com already open");
        |
        |      outMsgBuf = [];
        |
        |      websocket = new WebSocket("ws://localhost:$serverPort");
        |
        |      websocket.onopen = function(evt) {
        |        for (var i = 0; i < outMsgBuf.length; ++i)
        |          sendImpl(outMsgBuf[i]);
        |        outMsgBuf = null;
        |      };
        |      websocket.onclose = function(evt) {
        |        websocket = null;
        |        if (outMsgBuf !== null)
        |          throw new Error("WebSocket closed before being opened: " + evt);
        |        ${maybeExit(0)}
        |      };
        |      websocket.onmessage = recvImpl(recvCB);
        |      websocket.onerror = function(evt) {
        |        websocket = null;
        |        throw new Error("Websocket failed: " + evt);
        |      };
        |
        |      // Take over responsibility to auto exit
        |      window.callPhantom({
        |        action: 'setAutoExit',
        |        autoExit: false
        |      });
        |    },
        |    send: function(msg) {
        |      if (websocket === null)
        |        return; // we are closed already. ignore message
        |
        |      if (outMsgBuf !== null)
        |        outMsgBuf.push(msg);
        |      else
        |        sendImpl(msg);
        |    },
        |    close: function() {
        |      if (websocket === null)
        |        return; // we are closed already. all is well.
        |
        |      if (outMsgBuf !== null)
        |        // Reschedule ourselves to give onopen a chance to kick in
        |        window.setTimeout(window.scalajsCom.close, 10);
        |      else
        |        websocket.close();
        |    }
        |  }
        |}).call(this);""".stripMargin

      new MemVirtualJSFile("comSetup.js").withContent(code)
    }

    override def start(logger: Logger, console: JSConsole): Future[Unit] = {
      setupLoggerAndConsole(logger, console)
      mgr.start()
      startExternalJSEnv()
      future
    }

    def send(msg: String): Unit = synchronized {
      if (awaitConnection()) {
        val fragParts = msg.length / MaxCharPayloadSize

        for (i <- 0 until fragParts) {
          val payload = msg.substring(
              i * MaxCharPayloadSize, (i + 1) * MaxCharPayloadSize)
          mgr.sendMessage("1" + payload)
        }

        mgr.sendMessage("0" + msg.substring(fragParts * MaxCharPayloadSize))
      }
    }

    def receive(timeout: Duration): String = synchronized {
      if (recvBuf.isEmpty && !awaitConnection())
        throw new ComJSEnv.ComClosedException("Phantom.js isn't connected")

      val deadline = OptDeadline(timeout)

      @tailrec
      def loop(): String = {
        /* The fragments are accumulated in an instance-wide buffer in case
         * receiving a non-first fragment times out.
         */
        val frag = receiveFrag(deadline)
        fragmentsBuf ++= frag.substring(1)

        if (frag(0) == '0') {
          val result = fragmentsBuf.result()
          fragmentsBuf.clear()
          result
        } else if (frag(0) == '1') {
          loop()
        } else {
          throw new AssertionError("Bad fragmentation flag in " + frag)
        }
      }

      try {
        loop()
      } catch {
        case e: Throwable if !e.isInstanceOf[TimeoutException] =>
          fragmentsBuf.clear() // the protocol is broken, so discard the buffer
          throw e
      }
    }

    private def receiveFrag(deadline: OptDeadline): String = {
      while (recvBuf.isEmpty && !mgr.isClosed && !deadline.isOverdue)
        wait(deadline.millisLeft)

      if (recvBuf.isEmpty) {
        if (mgr.isClosed)
          throw new ComJSEnv.ComClosedException
        else
          throw new TimeoutException("Timeout expired")
      }

      recvBuf.dequeue()
    }

    def close(): Unit = mgr.stop()

    /** Waits until the JS VM has established a connection, or the VM
     *  terminated. Returns true if a connection was established.
     */
    private def awaitConnection(): Boolean = {
      while (!mgr.isConnected && !mgr.isClosed && isRunning)
        wait(10000)
      if (!mgr.isConnected && !mgr.isClosed && isRunning)
        throw new TimeoutException(
            "The PhantomJS WebSocket client took too long to connect")

      mgr.isConnected
    }

    override protected def initFiles(): Seq[VirtualJSFile] =
      super.initFiles :+ comSetup
  }

  protected trait AbstractPhantomRunner extends AbstractExtRunner {

    protected[this] val codeCache = new VirtualFileMaterializer

    override protected def getVMArgs(): Seq[String] = {
      // Add launcher file to arguments
      args :+ createTmpLauncherFile().getAbsolutePath
    }

    /** In phantom.js, we include JS using HTML */
    override protected def writeJSFile(file: VirtualJSFile, writer: Writer) = {
      val realFile = codeCache.materialize(file)
      val fname = htmlEscape(fixFileURI(realFile.toURI).toASCIIString)
      writer.write(
          s"""<script type="text/javascript" src="$fname"></script>""" + "\n")
    }

    /**
     * PhantomJS doesn't support Function.prototype.bind. We polyfill it.
     * https://github.com/ariya/phantomjs/issues/10522
     */
    override protected def initFiles(): Seq[VirtualJSFile] = Seq(
        // scalastyle:off line.size.limit
        new MemVirtualJSFile("bindPolyfill.js").withContent(
            """
            |// Polyfill for Function.bind from Facebook react:
            |// https://github.com/facebook/react/blob/3dc10749080a460e48bee46d769763ec7191ac76/src/test/phantomjs-shims.js
            |// Originally licensed under Apache 2.0
            |(function() {
            |
            |  var Ap = Array.prototype;
            |  var slice = Ap.slice;
            |  var Fp = Function.prototype;
            |
            |  if (!Fp.bind) {
            |    // PhantomJS doesn't support Function.prototype.bind natively, so
            |    // polyfill it whenever this module is required.
            |    Fp.bind = function(context) {
            |      var func = this;
            |      var args = slice.call(arguments, 1);
            |
            |      function bound() {
            |        var invokedAsConstructor = func.prototype && (this instanceof func);
            |        return func.apply(
            |          // Ignore the context parameter when invoking the bound function
            |          // as a constructor. Note that this includes not only constructor
            |          // invocations using the new keyword but also calls to base class
            |          // constructors such as BaseClass.call(this, ...) or super(...).
            |          !invokedAsConstructor && context || this,
            |          args.concat(slice.call(arguments))
            |        );
            |      }
            |
            |      // The bound function must share the .prototype of the unbound
            |      // function so that any object created by one constructor will count
            |      // as an instance of both constructors.
            |      bound.prototype = func.prototype;
            |
            |      return bound;
            |    };
            |  }
            |
            |})();
            |""".stripMargin
        ),
        new MemVirtualJSFile("scalaJSEnvInfo.js").withContent(
            """
            |__ScalaJSEnv = {
            |  exitFunction: function(status) {
            |    window.callPhantom({
            |      action: 'exit',
            |      returnValue: status | 0
            |    });
            |  }
            |};
            """.stripMargin
        )
        // scalastyle:on line.size.limit
    )

    protected def writeWebpageLauncher(out: Writer): Unit = {
      out.write(s"""<html><head>
          <title>Phantom.js Launcher</title>
          <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />""")
      sendJS(getJSFiles(), out)
      out.write(s"</head>\n<body></body>\n</html>\n")
    }

    protected def createTmpLauncherFile(): File = {
      val webF = createTmpWebpage()

      val launcherTmpF = File.createTempFile("phantomjs-launcher", ".js")
      launcherTmpF.deleteOnExit()

      val out = new FileWriter(launcherTmpF)

      try {
        out.write(
            s"""// Scala.js Phantom.js launcher
               |var page = require('webpage').create();
               |var url = "${escapeJS(fixFileURI(webF.toURI).toASCIIString)}";
               |var autoExit = $autoExit;
               |page.onConsoleMessage = function(msg) {
               |  console.log(msg);
               |};
               |page.onError = function(msg, trace) {
               |  console.error(msg);
               |  if (trace && trace.length) {
               |    console.error('');
               |    trace.forEach(function(t) {
               |      console.error('  ' + t.file + ':' + t.line +
               |        (t.function ? ' (in function "' + t.function +'")' : ''));
               |    });
               |  }
               |
               |  phantom.exit(2);
               |};
               |page.onCallback = function(data) {
               |  if (!data.action) {
               |    console.error('Called callback without action');
               |    phantom.exit(3);
               |  } else if (data.action === 'exit') {
               |    phantom.exit(data.returnValue || 0);
               |  } else if (data.action === 'setAutoExit') {
               |    if (typeof(data.autoExit) === 'boolean')
               |      autoExit = data.autoExit;
               |    else
               |      autoExit = true;
               |  } else {
               |    console.error('Unknown callback action ' + data.action);
               |    phantom.exit(4);
               |  }
               |};
               |page.open(url, function (status) {
               |  if (autoExit || status !== 'success')
               |    phantom.exit(status !== 'success');
               |});
               |""".stripMargin)
      } finally {
        out.close()
      }

      logger.debug(
          "PhantomJS using launcher at: " + launcherTmpF.getAbsolutePath())

      launcherTmpF
    }

    protected def createTmpWebpage(): File = {
      val webTmpF = File.createTempFile("phantomjs-launcher-webpage", ".html")
      webTmpF.deleteOnExit()

      val out = new BufferedWriter(
          new OutputStreamWriter(new FileOutputStream(webTmpF), "UTF-8"))

      try {
        writeWebpageLauncher(out)
      } finally {
        out.close()
      }

      logger.debug(
          "PhantomJS using webpage launcher at: " + webTmpF.getAbsolutePath())

      webTmpF
    }
  }

  protected def htmlEscape(str: String): String = str.flatMap {
    case '<' => "&lt;"
    case '>' => "&gt;"
    case '"' => "&quot;"
    case '&' => "&amp;"
    case c   => c :: Nil
  }

}

object PhantomJSEnv {
  private final val MaxByteMessageSize = 32768 // 32 KB
  private final val MaxCharMessageSize = MaxByteMessageSize / 2 // 2B per char
  private final val MaxCharPayloadSize = MaxCharMessageSize - 1 // frag flag

  private final val launcherName = "scalaJSPhantomJSEnvLauncher"

  final class Config private (
      val executable: String,
      val args: List[String],
      val env: Map[String, String],
      val autoExit: Boolean,
      val jettyClassLoader: ClassLoader
  ) {
    private def this() = {
      this(
          executable = "phantomjs",
          args = Nil,
          env = Map.empty,
          autoExit = true,
          jettyClassLoader = null
      )
    }

    def withExecutable(executable: String): Config =
      copy(executable = executable)

    def withArgs(args: List[String]): Config =
      copy(args = args)

    def withEnv(env: Map[String, String]): Config =
      copy(env = env)

    def withAutoExit(autoExit: Boolean): Config =
      copy(autoExit = autoExit)

    def withJettyClassLoader(jettyClassLoader: ClassLoader): Config =
      copy(jettyClassLoader = jettyClassLoader)

    private def copy(
        executable: String = executable,
        args: List[String] = args,
        env: Map[String, String] = env,
        autoExit: Boolean = autoExit,
        jettyClassLoader: ClassLoader = jettyClassLoader
    ): Config = {
      new Config(executable, args, env, autoExit, jettyClassLoader)
    }
  }

  object Config {
    /** Returns a default configuration for a [[PhantomJSEnv]].
     *
     *  The defaults are:
     *
     *  - `executable`: `"phantomjs"`
     *  - `args`: `Nil`
     *  - `env`: `Map.empty`
     *  - `autoExit`: `true`
     *  - `jettyClassLoader`: `null` (will use the current class loader)
     */
    def apply(): Config = new Config()
  }
}
