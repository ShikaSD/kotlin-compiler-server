package com.compiler.server.compiler.components

import androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar
import androidx.compose.compiler.plugins.kotlin.k2.ComposeFirExtensionRegistrar
import androidx.compose.compiler.plugins.kotlin.lower.IrSourcePrinterVisitor
import androidx.compose.compiler.plugins.kotlin.lower.dumpSrc
import com.compiler.server.model.CompilerDiagnostics
import com.compiler.server.model.ComposeIrCodegenResult
import com.intellij.mock.MockProject
import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.fir.FirDiagnosticsCompilerResultsReporter
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.js.klib.compileModuleToAnalyzedFirWithPsi
import org.jetbrains.kotlin.cli.js.klib.transformFirToIr
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.diagnostics.DiagnosticReporterFactory
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.ir.backend.js.MainModule
import org.jetbrains.kotlin.ir.backend.js.ModulesStructure
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.psi.KtFile
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ComposeIrGenerator {

    private val log = LoggerFactory.getLogger(LoggerDetailsStreamer::class.java)

    fun run(
        files: List<KtFile>,
        environment: KotlinCoreEnvironment,
        configuration: CompilerConfiguration
    ): ComposeIrCodegenResult {
        val libraries = configuration.getList(JSConfigurationKeys.LIBRARIES)
        val mainModule = MainModule.SourceFiles(files)
        val diagnosticsReporter = DiagnosticReporterFactory.createPendingReporter()
        val messageCollector = object : MessageCollector {
            override fun clear() {}

            override fun hasErrors(): Boolean = false

            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?
            ) {
                when (severity) {
                    CompilerMessageSeverity.EXCEPTION,
                    CompilerMessageSeverity.ERROR -> log.error(message)
                    CompilerMessageSeverity.STRONG_WARNING,
                    CompilerMessageSeverity.WARNING -> log.warn(message)
                    CompilerMessageSeverity.INFO -> log.info(message)
                    CompilerMessageSeverity.LOGGING -> log.debug(message)
                    CompilerMessageSeverity.OUTPUT -> log.info(message)
                }
            }

        }
        val moduleStructure = ModulesStructure(
            project = environment.project,
            mainModule = mainModule,
            compilerConfiguration = configuration,
            dependencies = libraries,
            friendDependenciesPaths = emptyList()
        )
        val analyzedModule = compileModuleToAnalyzedFirWithPsi(
            moduleStructure = moduleStructure,
            ktFiles = files,
            libraries = libraries,
            friendLibraries = emptyList(),
            diagnosticsReporter = diagnosticsReporter,
            incrementalDataProvider = null,
            lookupTracker = LookupTracker.DO_NOTHING,
            useWasmPlatform = true,
        )

        if (analyzedModule.reportCompilationErrors(moduleStructure, diagnosticsReporter, messageCollector)) {
            return ComposeIrCodegenResult(
                generatedIr = "",
                compilerDiagnostics = CompilerDiagnostics() // TODO figure out K2 diagnostics
            )
        }

        val irResult = transformFirToIr(moduleStructure, analyzedModule.output, diagnosticsReporter)
        FirDiagnosticsCompilerResultsReporter.reportToMessageCollector(diagnosticsReporter, messageCollector, true)
        if (diagnosticsReporter.hasErrors) {
            return ComposeIrCodegenResult(
                generatedIr = "",
                compilerDiagnostics = CompilerDiagnostics() // TODO figure out K2 diagnostics
            )
        }

        return ComposeIrCodegenResult(
            compilerDiagnostics = CompilerDiagnostics(),
            generatedIr = irResult.irModuleFragment.dumpSrc(useFir = true)
        )
    }
}