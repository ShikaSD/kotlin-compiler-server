import org.jetbrains.kotlin.analysis.project.structure.KtModule
import org.jetbrains.kotlin.sir.SirModule
import org.jetbrains.kotlin.sir.providers.SirSession
import org.jetbrains.kotlin.sir.providers.SirTypeProvider
import org.jetbrains.kotlin.sir.providers.impl.*
import org.jetbrains.kotlin.sir.providers.utils.SilentUnsupportedDeclarationReporter
import org.jetbrains.kotlin.sir.util.SirSwiftModule
import org.jetbrains.sir.lightclasses.SirDeclarationFromKtSymbolProvider

internal class PlaygroundSirSession(
    ktModule: KtModule,
) : SirSession {
    override val declarationNamer = SirDeclarationNamerImpl()
    override val moduleProvider = SirSingleModuleProvider("Playground")
    override val declarationProvider = CachingSirDeclarationProvider(
        declarationsProvider = SirDeclarationFromKtSymbolProvider(
            ktModule = ktModule,
            sirSession = sirSession,
        )
    )
    override val parentProvider = SirParentProviderImpl(
        sirSession = sirSession,
        packageEnumGenerator = SirEnumGeneratorImpl(SirSwiftModule)
    )
    override val typeProvider = SirTypeProviderImpl(
        errorTypeStrategy = SirTypeProvider.ErrorTypeStrategy.ErrorType,
        unsupportedTypeStrategy = SirTypeProvider.ErrorTypeStrategy.ErrorType,
        sirSession = sirSession,
    )
    override val visibilityChecker = SirVisibilityCheckerImpl(SilentUnsupportedDeclarationReporter)
    override val childrenProvider = SirDeclarationChildrenProviderImpl(
        sirSession = sirSession,
    )
}