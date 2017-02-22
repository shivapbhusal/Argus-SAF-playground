package org.argus.play.random

import java.io.File

import org.argus.amandroid.alir.pta.reachingFactsAnalysis.{AndroidRFAConfig, AndroidReachingFactsAnalysis}
import org.argus.amandroid.alir.taintAnalysis.{AndroidDataDependentTaintAnalysis, DataLeakageAndroidSourceAndSinkManager}
import org.argus.amandroid.core.{AndroidGlobalConfig, Apk}
import org.argus.amandroid.core.appInfo.AppInfoCollector
import org.argus.amandroid.core.decompile.{ApkDecompiler, DecompileLayout, DecompilerSettings}
import org.argus.amandroid.core.util.AndroidLibraryAPISummary
import org.argus.jawa.alir.Context
import org.argus.jawa.alir.controlFlowGraph.{ICFGCallNode, ICFGInvokeNode}
import org.argus.jawa.alir.dataDependenceAnalysis.InterproceduralDataDependenceAnalysis
import org.argus.jawa.alir.pta.{PTAConcreteStringInstance, VarSlot}
import org.argus.jawa.alir.pta.reachingFactsAnalysis.RFAFactFactory
import org.argus.jawa.core._
import org.sireum.util._

/**
  * Created by fgwei on 2/22/17.
  */
object RetriveStringFromSourceOrSink {
  def loadCode(apkUri: FileResourceUri, settings: DecompilerSettings, global: Global): (FileResourceUri, ISet[String]) = {
    val (outUri, srcs, _) = ApkDecompiler.decompile(apkUri, settings)
    srcs foreach {
      src =>
        val fileUri = FileUtil.toUri(FileUtil.toFilePath(outUri) + File.separator + src)
        if(FileUtil.toFile(fileUri).exists()) {
          //store the app's jawa code in global which is organized class by class.
          global.load(fileUri, Constants.JAWA_FILE_EXT, AndroidLibraryAPISummary)
        }
    }
    (outUri, srcs)
  }

  def loadApk(apkUri: FileResourceUri, settings: DecompilerSettings, global: Global): Apk = {
    val (outUri, srcs) = loadCode(apkUri, settings, global)
    val apk = new Apk(apkUri, outUri, srcs)
    AppInfoCollector.collectInfo(apk, global, outUri)
    apk
  }

  def main(args: Array[String]): Unit = {
    val fileUri = FileUtil.toUri(getClass.getResource("/random/ReadInternet.apk").getPath)
    val outputUri = FileUtil.toUri(getClass.getResource("/output").getPath)

    /******************* Load APK *********************/

    val reporter = new DefaultReporter
    // Global is the class loader and class path manager
    val global = new Global(fileUri, reporter)
    global.setJavaLib(AndroidGlobalConfig.settings.lib_files)
    val layout = DecompileLayout(outputUri)
    val settings = DecompilerSettings(
      AndroidGlobalConfig.settings.dependence_dir.map(FileUtil.toUri),
      dexLog = false, debugMode = false, removeSupportGen = true,
      forceDelete = false, None, layout)
    val apk = loadApk(fileUri, settings, global)


    /******************* Do Taint analysis *********************/

    val component = apk.getComponents.head // get any component you want to perform analysis
    apk.getEnvMap.get(component) match {
      case Some((esig, _)) =>
        val ep = global.getMethod(esig).get
        implicit val factory = new RFAFactFactory
        val initialfacts = AndroidRFAConfig.getInitialFactsForMainEnvironment(ep)
        val idfg = AndroidReachingFactsAnalysis(global, apk, ep, initialfacts, new ClassLoadManager, timeout = None)
        val iddResult = InterproceduralDataDependenceAnalysis(global, idfg)
        val ssm = new DataLeakageAndroidSourceAndSinkManager(global, apk, apk.getLayoutControls, apk.getCallbackMethods, AndroidGlobalConfig.settings.sas_file)
        val taint_analysis_result = AndroidDataDependentTaintAnalysis(global, iddResult, idfg.ptaresult, ssm)

        /******************* Resolve all URL value *********************/

        val urlMap: MMap[Context, MSet[String]] = mmapEmpty
        idfg.icfg.nodes foreach {
          case cn: ICFGCallNode if cn.getCalleeSig == new Signature("Ljava/net/URL;.<init>:(Ljava/lang/String;)V") =>
            val urlSlot = VarSlot(cn.argNames.head, isBase = false, isArg = true)
            val urls = idfg.ptaresult.pointsToSet(urlSlot, cn.getContext)
            val strSlot = VarSlot(cn.argNames(1), isBase = false, isArg = true)
            val urlvalues = idfg.ptaresult.pointsToSet(strSlot, cn.getContext) map {
              case pcsi: PTAConcreteStringInstance => pcsi.string
              case _ => "ANY"
            }
            for(url <- urls;
                urlvalue <- urlvalues) {
              urlMap.getOrElseUpdate(url.defSite, msetEmpty) += urlvalue
            }
          case _ =>
        }

        /******************* Retrieve URL value *********************/

        val gisNodes = taint_analysis_result.getSourceNodes.filter{
          node =>
            node.node.getICFGNode match {
              case cn: ICFGInvokeNode if cn.getCalleeSig == new Signature("Ljava/net/URLConnection;.getInputStream:()Ljava/io/InputStream;") =>
                true
              case _ => false
            }
        }
        gisNodes.foreach {
          node =>
            val invNode = node.node.getICFGNode.asInstanceOf[ICFGInvokeNode]
            val connSlot = VarSlot(invNode.argNames.head, isBase = false, isArg = true)
            val connValues = idfg.ptaresult.pointsToSet(connSlot, invNode.getContext)
            connValues foreach {
              connValue =>
                val urlInvNode = idfg.icfg.getICFGCallNode(connValue.defSite).asInstanceOf[ICFGCallNode]
                val urlSlot = VarSlot(urlInvNode.argNames.head, isBase = false, isArg = true)
                val urlValues = idfg.ptaresult.pointsToSet(urlSlot, connValue.defSite)
                urlValues foreach {
                  urlValue =>
                    println("URL value at " + node + " is: " + urlMap.getOrElse(urlValue.defSite, msetEmpty))
                }
            }
        }
      case None =>
        global.reporter.error("TaintAnalysis", "Component " + component + " did not have environment! Some package or name mismatch maybe in the Manifest file.")
    }
  }
}