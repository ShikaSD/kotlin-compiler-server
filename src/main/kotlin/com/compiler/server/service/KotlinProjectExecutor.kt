package com.compiler.server.service

import androidx.compose.compiler.plugins.kotlin.ComposeConfiguration
import com.compiler.server.compiler.KotlinFile
import com.compiler.server.compiler.components.*
import com.compiler.server.model.*
import com.compiler.server.model.bean.VersionInfo
import com.intellij.openapi.util.Disposer
import component.KotlinEnvironment
import model.Completion
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.psi.KtFile
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class KotlinProjectExecutor(
  private val kotlinCompiler: KotlinCompiler,
  private val completionProvider: CompletionProvider,
  private val version: VersionInfo,
  private val kotlinToJSTranslator: KotlinToJSTranslator,
  private val swiftExportTranslator: SwiftExportTranslator,
  private val composeIrGenerator: ComposeIrGenerator,
  private val kotlinEnvironment: KotlinEnvironment,
  private val loggerDetailsStreamer: LoggerDetailsStreamer? = null,
) {

  private val log = LoggerFactory.getLogger(KotlinProjectExecutor::class.java)

  fun run(project: Project): ExecutionResult {
    return kotlinEnvironment.environment { environment ->
      val files = getFilesFrom(project, environment).map { it.kotlinFile }
      kotlinCompiler.run(files, project.args)
    }.also { logExecutionResult(project, it) }
  }

  fun test(project: Project): ExecutionResult {
    return kotlinEnvironment.environment { environment ->
      val files = getFilesFrom(project, environment).map { it.kotlinFile }
      kotlinCompiler.test(files)
    }.also { logExecutionResult(project, it) }
  }

  fun convertToJsIr(project: Project): TranslationJSResult {
    return convertJsWithConverter(project, kotlinToJSTranslator::doTranslateWithIr)
  }

  fun compileToJvm(project: Project): CompilationResult<KotlinCompiler.JvmClasses> {
    val files = kotlinEnvironment.environment { environment ->
      getFilesFrom(project, environment).map { it.kotlinFile }
    }
    return kotlinCompiler.compile(files)
  }

  fun convertToWasm(project: Project, debugInfo: Boolean): TranslationResultWithJsCode {
    return convertWasmWithConverter(project, debugInfo, kotlinToJSTranslator::doTranslateWithWasm)
  }

  fun convertToSwift(project: Project): SwiftExportResult {
    return convertSwiftWithConverter(project)
  }

  fun convertToComposeIr(project: Project): ComposeIrCodegenResult {
    return convertComposeIr(project)
  }

  fun complete(project: Project, line: Int, character: Int): List<Completion> {
    return kotlinEnvironment.environment {
      val file = getFilesFrom(project, it).first()
      try {
        val projectType = if (project.confType == ProjectType.SWIFT_EXPORT) ProjectType.COMPOSE_WASM else project.confType
        completionProvider.complete(file, line, character, projectType, it)
      } catch (e: Exception) {
        log.warn("Exception in getting completions. Project: $project", e)
        emptyList()
      }
    }
  }

  fun highlight(project: Project): CompilerDiagnostics = try {
    when (project.confType) {
      ProjectType.JAVA, ProjectType.JUNIT -> compileToJvm(project).compilerDiagnostics
      ProjectType.CANVAS, ProjectType.JS, ProjectType.JS_IR ->
        convertToJsIr(
          project,
        ).compilerDiagnostics
      ProjectType.WASM, ProjectType.COMPOSE_WASM ->
        convertToWasm(
          project,
          debugInfo = false,
        ).compilerDiagnostics
      ProjectType.SWIFT_EXPORT -> convertToSwift(project).compilerDiagnostics
    }
  } catch (e: Exception) {
    log.warn("Exception in getting highlight. Project: $project", e)
    CompilerDiagnostics(emptyMap())
  }

  fun getVersion() = version

  private fun convertJsWithConverter(
    project: Project,
    converter: (List<KtFile>, List<String>) -> CompilationResult<String>
  ): TranslationJSResult {
    return kotlinEnvironment.environment { environment ->
      val files = getFilesFrom(project, environment).map { it.kotlinFile }
      kotlinToJSTranslator.translateJs(
        files,
        project.args.split(" "),
        converter
      )
    }.also { logExecutionResult(project, it) }
  }

  private fun convertWasmWithConverter(
    project: Project,
    debugInfo: Boolean,
    converter: (List<KtFile>, List<String>, List<String>, List<String>) -> CompilationResult<WasmTranslationSuccessfulOutput>
  ): TranslationResultWithJsCode {
    return kotlinEnvironment.environment { environment ->
      val files = getFilesFrom(project, environment).map { it.kotlinFile }
      kotlinToJSTranslator.translateWasm(
        files,
        debugInfo,
        project.confType,
        converter
      )
    }.also { logExecutionResult(project, it) }
  }

  private fun convertSwiftWithConverter(
    project: Project,
  ): SwiftExportResult {
    return kotlinEnvironment.environment { environment ->
      val files = getFilesFrom(project, environment).map { it.kotlinFile }
      swiftExportTranslator.translate(files)
    }.also { logExecutionResult(project, it) }
  }

  private fun logExecutionResult(project: Project, executionResult: ExecutionResult) {
    loggerDetailsStreamer?.logExecutionResult(
      executionResult,
      project.confType,
      getVersion().version
    )
  }

  private fun convertComposeIr(
    project: Project
  ): ComposeIrCodegenResult {
    val configuration = kotlinEnvironment.composeWasmConfiguration.copy()
    configuration.languageVersionSettings = LanguageVersionSettingsImpl(LanguageVersion.LATEST_STABLE, ApiVersion.LATEST_STABLE)
    configuration.put(ComposeConfiguration.STRONG_SKIPPING_ENABLED_KEY, true)
    configuration.put(ComposeConfiguration.SOURCE_INFORMATION_ENABLED_KEY, true)
    val env = KotlinCoreEnvironment.createForProduction(
      projectDisposable = Disposer.newDisposable(),
      configuration = configuration,
      configFiles = EnvironmentConfigFiles.JS_CONFIG_FILES
    )
    val files = getFilesFrom(project, env).map { it.kotlinFile }
    val result = composeIrGenerator.run(files, env, configuration)
    logExecutionResult(project, result)
    return result
  }

  private fun getFilesFrom(project: Project, coreEnvironment: KotlinCoreEnvironment) = project.files.map {
    KotlinFile.from(coreEnvironment.project, it.name, it.text)
  }
}
