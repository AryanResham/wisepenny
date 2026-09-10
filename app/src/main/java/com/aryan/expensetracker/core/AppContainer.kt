package com.aryan.expensetracker.core

import android.content.Context
import android.net.ConnectivityManager
import androidx.room.Room
import com.aryan.expensetracker.BuildConfig
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.AppDatabase
import com.aryan.expensetracker.core.llm.GeminiClient
import com.aryan.expensetracker.core.llm.LlmClient
import com.aryan.expensetracker.core.location.LocationProvider
import com.aryan.expensetracker.core.net.NetworkChecker
import com.aryan.expensetracker.core.prefs.AppPrefs
import com.aryan.expensetracker.feature.categories.CategoryDao
import com.aryan.expensetracker.feature.transactions.TransactionDao
import com.aryan.expensetracker.pipeline.capture.BankMessageFilter
import com.aryan.expensetracker.pipeline.capture.InboxReader
import com.aryan.expensetracker.pipeline.capture.MessageCapture
import com.aryan.expensetracker.pipeline.capture.PendingMessageDao
import com.aryan.expensetracker.pipeline.capture.ProcessedMessageDao
import com.aryan.expensetracker.pipeline.categorization.LlmCategorizer
import com.aryan.expensetracker.pipeline.categorization.LocalCategorizer
import com.aryan.expensetracker.pipeline.categorization.MerchantNormalizer
import com.aryan.expensetracker.pipeline.categorization.MerchantRuleDao
import com.aryan.expensetracker.pipeline.dedup.DuplicateChecker
import com.aryan.expensetracker.pipeline.extraction.LlmExtractor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class AppContainer(context: Context) {

    val json: Json = Json { ignoreUnknownKeys = true }

    // v1 accepts data loss on a schema change; there is nothing to migrate yet
    private val database: AppDatabase = Room.databaseBuilder(
        context, AppDatabase::class.java, AppConfig.DATABASE_NAME
    ).fallbackToDestructiveMigration(dropAllTables = true).build()

    val transactionDao: TransactionDao = database.transactionDao()
    val categoryDao: CategoryDao = database.categoryDao()
    val merchantRuleDao: MerchantRuleDao = database.merchantRuleDao()
    val pendingMessageDao: PendingMessageDao = database.pendingMessageDao()
    val processedMessageDao: ProcessedMessageDao = database.processedMessageDao()

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(AppConfig.GEMINI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val networkChecker: NetworkChecker = NetworkChecker(
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    )

    val appPrefs: AppPrefs = AppPrefs(context)

    val bankMessageFilter: BankMessageFilter = BankMessageFilter()

    val locationProvider: LocationProvider = LocationProvider(context.applicationContext)

    val messageCapture: MessageCapture = MessageCapture(
        pendingMessageDao,
        processedMessageDao,
        bankMessageFilter,
        locationProvider,
        appPrefs,
        context.applicationContext,
    )

    val inboxReader: InboxReader = InboxReader(context.applicationContext, messageCapture, appPrefs)

    val llmClient: LlmClient = GeminiClient(httpClient, appPrefs, BuildConfig.GEMINI_API_KEY, json)
    val llmExtractor: LlmExtractor = LlmExtractor(llmClient, json)
    val merchantNormalizer: MerchantNormalizer = MerchantNormalizer(AppConfig.MERCHANT_NOISE_WORDS)
    val localCategorizer: LocalCategorizer = LocalCategorizer(merchantRuleDao)
    val llmCategorizer: LlmCategorizer = LlmCategorizer(llmClient, categoryDao, merchantRuleDao, json)
    val duplicateChecker: DuplicateChecker = DuplicateChecker(transactionDao)

    // later tasks add the pipeline here
}
