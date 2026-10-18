package com.aryan.expensetracker.core

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import com.aryan.expensetracker.BuildConfig
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.AppDatabase
import com.aryan.expensetracker.core.llm.AgentTools
import com.aryan.expensetracker.core.llm.GeminiAgent
import com.aryan.expensetracker.core.llm.GeminiClient
import com.aryan.expensetracker.core.llm.LlmClient
import com.aryan.expensetracker.core.location.LocationProvider
import com.aryan.expensetracker.core.net.AndroidNetworkChecker
import com.aryan.expensetracker.core.net.NetworkChecker
import com.aryan.expensetracker.core.prefs.AppPrefs
import com.aryan.expensetracker.feature.transactions.CaptureMark
import com.aryan.expensetracker.feature.transactions.TransactionDao
import com.aryan.expensetracker.pipeline.DatabaseWriter
import com.aryan.expensetracker.pipeline.MessagePipeline
import com.aryan.expensetracker.pipeline.capture.BankMessageFilter
import com.aryan.expensetracker.pipeline.capture.InboxReader
import com.aryan.expensetracker.pipeline.capture.MessageCapture
import com.aryan.expensetracker.pipeline.capture.PendingMessageDao
import com.aryan.expensetracker.pipeline.capture.ProcessedMessageDao
import com.aryan.expensetracker.pipeline.extraction.FallbackCounter
import com.aryan.expensetracker.pipeline.extraction.LlmFallbackExtractor
import com.aryan.expensetracker.pipeline.extraction.MessageTemplate
import com.aryan.expensetracker.pipeline.extraction.NonTransactionFilter
import com.aryan.expensetracker.pipeline.extraction.RegexExtractor
import com.aryan.expensetracker.pipeline.extraction.loadTemplates
import com.aryan.expensetracker.pipeline.tagging.AutoTagger
import com.aryan.expensetracker.pipeline.tagging.PayeeNormalizer
import com.aryan.expensetracker.pipeline.tagging.PayeeRuleDao
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

private const val TAG = "AppContainer"

class AppContainer(context: Context) {

    val json: Json = Json { ignoreUnknownKeys = true }

    // v1 data is disposable, so a schema change drops the tables instead of migrating them
    val database: AppDatabase = Room.databaseBuilder(
        context, AppDatabase::class.java, AppConfig.DATABASE_NAME
    ).fallbackToDestructiveMigration(dropAllTables = true).build()

    val transactionDao: TransactionDao = database.transactionDao()
    val payeeRuleDao: PayeeRuleDao = database.payeeRuleDao()
    val pendingMessageDao: PendingMessageDao = database.pendingMessageDao()
    val processedMessageDao: ProcessedMessageDao = database.processedMessageDao()

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(AppConfig.GEMINI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val networkChecker: NetworkChecker = AndroidNetworkChecker(
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    )

    val appPrefs: AppPrefs = AppPrefs(context)

    val captureMark: CaptureMark = CaptureMark { appPrefs.getLastSeenSmsAt() }

    val bankMessageFilter: BankMessageFilter = BankMessageFilter()

    val locationProvider: LocationProvider = LocationProvider(context.applicationContext)

    val messageCapture: MessageCapture = MessageCapture(
        pendingMessageDao,
        processedMessageDao,
        bankMessageFilter,
        locationProvider,
        appPrefs,
    )

    val inboxReader: InboxReader = InboxReader(context.applicationContext, messageCapture, appPrefs)

    val llmClient: LlmClient = GeminiClient(httpClient, appPrefs, BuildConfig.GEMINI_API_KEY, json)

    private val templates: List<MessageTemplate> =
        loadTemplates(readTemplatesAsset(context), json)

    val nonTransactionFilter: NonTransactionFilter = NonTransactionFilter()
    val regexExtractor: RegexExtractor = RegexExtractor(templates)
    val llmFallbackExtractor: LlmFallbackExtractor = LlmFallbackExtractor(
        llmClient,
        json,
        FallbackCounter { appPrefs.incrementLlmFallbackCount() },
    )

    val payeeNormalizer: PayeeNormalizer = PayeeNormalizer()
    val autoTagger: AutoTagger = AutoTagger(payeeRuleDao, transactionDao)

    val databaseWriter: DatabaseWriter = DatabaseWriter { block -> database.withTransaction(block) }

    // chat: the agent drives the tool loop, AgentTools runs the queries and the confirmed write
    val agentTools: AgentTools = AgentTools(transactionDao, payeeRuleDao, json)
    val geminiAgent: GeminiAgent = GeminiAgent(llmClient, json)
    val hasGeminiKey: Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    val messagePipeline: MessagePipeline = MessagePipeline(
        transactionDao,
        processedMessageDao,
        networkChecker,
        nonTransactionFilter,
        regexExtractor,
        llmFallbackExtractor,
        payeeNormalizer,
        autoTagger,
        databaseWriter,
    )
}

// a missing or unreadable asset degrades to no templates, which only means the model does more work
private fun readTemplatesAsset(context: Context): String {
    return try {
        context.assets.open(AppConfig.TEMPLATES_ASSET).bufferedReader().use { it.readText() }
    } catch (error: Exception) {
        Log.e(TAG, "templates asset unreadable: ${error.javaClass.simpleName}")
        ""
    }
}
