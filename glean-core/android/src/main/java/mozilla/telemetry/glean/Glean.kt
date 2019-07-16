/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.telemetry.glean

import android.util.Log
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ProcessLifecycleOwner
import com.sun.jna.StringArray
import kotlinx.coroutines.Job
import mozilla.components.support.ktx.android.content.isMainProcess
import mozilla.telemetry.glean.config.Configuration
import mozilla.telemetry.glean.utils.getLocaleTag
import java.io.File
import mozilla.telemetry.glean.rust.LibGleanFFI
import mozilla.telemetry.glean.rust.MetricHandle
import mozilla.telemetry.glean.rust.RustError
import mozilla.telemetry.glean.rust.getAndConsumeRustString
import mozilla.telemetry.glean.rust.toBoolean
import mozilla.telemetry.glean.rust.toByte
import mozilla.telemetry.glean.GleanMetrics.GleanBaseline
import mozilla.telemetry.glean.GleanMetrics.GleanInternalMetrics
import mozilla.telemetry.glean.GleanMetrics.Pings
import mozilla.telemetry.glean.private.PingType
import mozilla.telemetry.glean.private.RecordedExperimentData
import mozilla.telemetry.glean.scheduler.GleanLifecycleObserver
import mozilla.telemetry.glean.scheduler.PingUploadWorker
import org.json.JSONObject

// Public exported type identifying individual timers for [TimingDistributionMetricType].
typealias GleanTimerId = Long

@Suppress("TooManyFunctions")
open class GleanInternalAPI internal constructor () {
    companion object {
        private val LOG_TAG: String = "glean/Glean"
        internal const val GLEAN_DATA_DIR: String = "glean_data"
    }

    // `internal` so this can be modified for testing
    internal var handle: MetricHandle = 0L

    internal lateinit var configuration: Configuration

    private val gleanLifecycleObserver by lazy { GleanLifecycleObserver() }

    private lateinit var gleanDataDir: File

    // Keep track of this flag before Glean is initialized
    private var uploadEnabled: Boolean = true

    // This object holds data related to any persistent information about the metrics ping,
    // such as the last time it was sent and the store name
    // TODO: 1551159 Integrate MetricsPingScheduler
    // internal lateinit var metricsPingScheduler: MetricsPingScheduler

    // Keep track of ping types that have been registered before Glean is initialized.
    private val pingTypeQueue: MutableList<PingType> = mutableListOf()

    /**
     * Initialize the Glean SDK.
     *
     * This should only be initialized once by the application, and not by
     * libraries using the Glean SDK. A message is logged to error and no
     * changes are made to the state if initialize is called a more than
     * once.
     *
     * A LifecycleObserver will be added to send pings when the application goes
     * into the background.
     *
     * @param applicationContext [Context] to access application features, such
     * as shared preferences
     * @param configuration A Glean [Configuration] object with global settings.
     */
    @Suppress("ReturnCount")
    @Synchronized
    fun initialize(
        applicationContext: Context,
        configuration: Configuration = Configuration()
    ) {
        // In certain situations Glean.initialize may be called from a process other than the main
        // process.  In this case we want initialize to be a no-op and just return.
        if (!applicationContext.isMainProcess()) {
            Log.e(LOG_TAG, "Attempted to initialize Glean on a process other than the main process")
            return
        }

        if (isInitialized()) {
            Log.e(LOG_TAG, "Glean should not be initialized multiple times")
            return
        }

        registerPings(Pings)

        this.configuration = configuration

        this.gleanDataDir = File(applicationContext.applicationInfo.dataDir, GLEAN_DATA_DIR)
        handle = LibGleanFFI.INSTANCE.glean_initialize(
            this.gleanDataDir.path,
            applicationContext.packageName,
            uploadEnabled.toByte()
        )

        // If initialization of Glean fails we bail out and don't initialize further.
        if (handle == 0L) {
            return
        }

        // If any pings were registered before initializing, do so now
        this.pingTypeQueue.forEach { this.registerPingType(it) }
        if (!Dispatchers.API.testingMode) {
            this.pingTypeQueue.clear()
        }

        // TODO: on glean-legacy we perform other actions before initialize the metrics (e.g.
        // init the engines), then init the core metrics, and finally kick off the metrics
        // schedulers. We should do something similar here as well.
        initializeCoreMetrics(applicationContext)

        // Deal with any pending events so we can start recording new ones
        LibGleanFFI.INSTANCE.glean_on_ready_to_send_pings(this.handle)

        // Set up information and scheduling for Glean owned pings. Ideally, the "metrics"
        // ping startup check should be performed before any other ping, since it relies
        // on being dispatched to the API context before any other metric.
        // TODO: 1551159 Integrate MetricsPingScheduler
        // metricsPingScheduler = MetricsPingScheduler(applicationContext)
        // metricsPingScheduler.startupCheck()

        // At this point, all metrics and events can be recorded.
        ProcessLifecycleOwner.get().lifecycle.addObserver(gleanLifecycleObserver)
    }

    /**
     * Returns true if the Glean SDK has been initialized.
     */
    internal fun isInitialized(): Boolean {
        return handle != 0L
    }

    /**
     * Register the pings generated from `pings.yaml` with the Glean SDK.
     *
     * @param pings The `Pings` object generated for your library or application
     * by the Glean SDK.
     */
    fun registerPings(pings: Any) {
        // Instantiating the Pings object to send this function is enough to
        // call the constructor and have it registered through [Glean.registerPingType].
        Log.i(LOG_TAG, "Registering pings for ${pings.javaClass.canonicalName}")
    }

    /**
     * Enable or disable Glean collection and upload.
     *
     * Metric collection is enabled by default.
     *
     * When uploading is disabled, metrics aren't recorded at all and no data
     * is uploaded.
     *
     * When disabling, all pending metrics, events and queued pings are cleared.
     *
     * When enabling, the core Glean metrics are recreated.
     *
     * @param enabled When true, enable metric collection.
     */
    fun setUploadEnabled(enabled: Boolean) {
        if (isInitialized()) {
            LibGleanFFI.INSTANCE.glean_set_upload_enabled(handle, enabled.toByte())
        } else {
            uploadEnabled = enabled
        }
    }

    /**
     * Get whether or not Glean is allowed to record and upload data.
     */
    fun getUploadEnabled(): Boolean {
        if (isInitialized()) {
            return LibGleanFFI.INSTANCE.glean_is_upload_enabled(handle).toBoolean()
        } else {
            return uploadEnabled
        }
    }

    /**
     * Indicate that an experiment is running. Glean will then add an
     * experiment annotation to the environment which is sent with pings. This
     * information is not persisted between runs.
     *
     * @param experimentId The id of the active experiment (maximum
     *     30 bytes)
     * @param branch The experiment branch (maximum 30 bytes)
     * @param extra Optional metadata to output with the ping
     */
    fun setExperimentActive(
        experimentId: String,
        branch: String,
        extra: Map<String, String>? = null
    ) {
        if (!isInitialized()) {
            Log.e(LOG_TAG, "Please call Glean.initialize() before using this API")
            return
        }

        // The 'extra' map is sent over FFI as a pair of arrays, one containing
        // the keys and the other containing the values.
        var keys: StringArray? = null
        var values: StringArray? = null
        var numKeys = 0

        extra?.let {
            numKeys = extra.size
            keys = StringArray(extra.keys.toTypedArray(), "utf-8")
            values = StringArray(extra.values.toTypedArray(), "utf-8")
        }

        LibGleanFFI.INSTANCE.glean_set_experiment_active(
            handle,
            experimentId,
            branch,
            keys,
            values,
            numKeys
        )
    }

    /**
     * Indicate that an experiment is no longer running.
     *
     * @param experimentId The id of the experiment to deactivate.
     */
    fun setExperimentInactive(experimentId: String) {
        if (!isInitialized()) {
            Log.e(LOG_TAG, "Please call Glean.initialize() before using this API")
            return
        }

        LibGleanFFI.INSTANCE.glean_set_experiment_inactive(handle, experimentId)
    }

    /**
     * Tests whether an experiment is active, for testing purposes only.
     *
     * @param experimentId the id of the experiment to look for.
     * @return true if the experiment is active and reported in pings, otherwise false
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun testIsExperimentActive(experimentId: String): Boolean {
        @Suppress("EXPERIMENTAL_API_USAGE")
        Dispatchers.API.assertInTestingMode()

        return LibGleanFFI.INSTANCE.glean_experiment_test_is_active(handle, experimentId).toBoolean()
    }

    /**
    * Returns the stored data for the requested active experiment, for testing purposes only.
    *
    * @param experimentId the id of the experiment to look for.
    * @return the [RecordedExperimentData] for the experiment
    * @throws [NullPointerException] if the requested experiment is not active or data is corrupt.
    */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun testGetExperimentData(experimentId: String): RecordedExperimentData {
        @Suppress("EXPERIMENTAL_API_USAGE")
        // Dispatchers.API.assertInTestingMode()
        Dispatchers.API.assertInTestingMode()

        val ptr = LibGleanFFI.INSTANCE.glean_experiment_test_get_data(
            handle,
            experimentId
        )!!

        var branchId: String? = null
        var extraMap: Map<String, String>? = null
        try {
            // Parse and extract the fields from the JSON string here so
            // that we can always throw NullPointerException if something
            // goes wrong.
            val jsonRes = JSONObject(ptr.getAndConsumeRustString())
            branchId = jsonRes.getString("branch")
            extraMap = jsonRes.optJSONObject("extra")?.let {
                val map = mutableMapOf<String, String>()
                it.names()?.let { names ->
                    for (i in 0 until names.length()) {
                        map[names.getString(i)] = it.getString(names.getString(i))
                    }
                }
                map
            }
        } catch (e: org.json.JSONException) {
            throw NullPointerException()
        }

        return RecordedExperimentData(branchId, extraMap)
    }

    /**
     * Initialize the core metrics internally managed by Glean (e.g. client id).
     */
    private fun initializeCoreMetrics(applicationContext: Context) {
        // Set a few more metrics that will be sent as part of every ping.
        // Please note that the following metrics must be set synchronously, so
        // that they are guaranteed to be available with the first ping that is
        // generated. We use an internal only API to do that.
        GleanBaseline.locale.setSync(getLocaleTag())
        GleanInternalMetrics.os.setSync("Android")
        // https://developer.android.com/reference/android/os/Build.VERSION
        GleanInternalMetrics.androidSdkVersion.setSync(Build.VERSION.SDK_INT.toString())
        GleanInternalMetrics.osVersion.setSync(Build.VERSION.RELEASE)
        // https://developer.android.com/reference/android/os/Build
        GleanInternalMetrics.deviceManufacturer.setSync(Build.MANUFACTURER)
        GleanInternalMetrics.deviceModel.setSync(Build.MODEL)
        GleanInternalMetrics.architecture.setSync(Build.SUPPORTED_ABIS[0])

        configuration.channel?.let {
            GleanInternalMetrics.appChannel.setSync(it)
        }

        try {
            val packageInfo = applicationContext.packageManager.getPackageInfo(
                    applicationContext.packageName, 0
            )
            @Suppress("DEPRECATION")
            GleanInternalMetrics.appBuild.setSync(packageInfo.versionCode.toString())

            GleanInternalMetrics.appDisplayVersion.setSync(
                    packageInfo.versionName?.let { it } ?: "Unknown"
            )
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(
                LOG_TAG,
                "Could not get own package info, unable to report build id and display version"
            )
            throw AssertionError("Could not get own package info, aborting init")
        }
    }

    /**
     * Get the data directory for Glean.
     */
    internal fun getDataDir(): File {
        return this.gleanDataDir
    }

    /**
     * Collect a ping and return a string
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun testCollect(ping: PingType): String? {
        return LibGleanFFI.INSTANCE.glean_ping_collect(handle, ping.handle)?.getAndConsumeRustString()
    }

    /**
     * Handle the background event and send the appropriate pings.
     */
    fun handleBackgroundEvent() {
        sendPings(listOf(Pings.baseline, Pings.events))
    }

    private fun <T> sendPingsGeneric(
        pings: List<T>,
        pingSender: (T) -> Boolean,
        nameFn: (T) -> String
    ) = Dispatchers.API.launch {
        if (!isInitialized()) {
            Log.e(LOG_TAG, "Glean must be initialized before sending pings.")
            return@launch
        }

        if (!getUploadEnabled()) {
            Log.e(LOG_TAG, "Glean must be enabled before sending pings.")
            return@launch
        }

        // TODO: 1553813: glean-ac collects and stores pings in parallel and
        // then joins them all before queueing the worker. This here is writing them out
        // sequentially.
        var sentPing = false
        for (ping in pings) {
            if (pingSender(ping)) {
                sentPing = true
            } else {
                Log.d(LOG_TAG, "No content for ping '${nameFn(ping)}', therefore no ping queued.")
            }
        }

        if (sentPing) {
            PingUploadWorker.enqueueWorker()
        }
    }

    /**
     * Send a list of pings.
     *
     * The ping content is assembled as soon as possible, but upload is not
     * guaranteed to happen immediately, as that depends on the upload
     * policies.
     *
     * If the ping currently contains no content, it will not be assembled and
     * queued for sending.
     *
     * @param pings List of pings to send.
     * @return The async [Job] performing the work of assembling the ping
     */
    internal fun sendPings(pings: List<PingType>): Job? {
        val sendPing: (PingType) -> Boolean = {
            LibGleanFFI.INSTANCE.glean_send_ping(
                handle,
                it.handle,
                (configuration.logPings).toByte()
            ).toBoolean()
        }
        val nameFn: (PingType) -> String = { it.name }

        return sendPingsGeneric(pings, sendPing, nameFn)
    }

    /**
     * Send a list of pings by name.
     *
     * Each ping will be looked up in the known instances of [PingType]. If the
     * ping isn't known, an error is logged and the ping isn't queued for uploading.
     *
     * The ping content is assembled as soon as possible, but upload is not
     * guaranteed to happen immediately, as that depends on the upload
     * policies.
     *
     * If the ping currently contains no content, it will not be assembled and
     * queued for sending.
     *
     * @param pingNames List of ping names to send.
     * @return The async [Job] performing the work of assembling the ping
     */
    internal fun sendPingsByName(pingNames: List<String>): Job? {
        val sendPing: (String) -> Boolean = {
            LibGleanFFI.INSTANCE.glean_send_ping_by_name(
                handle,
                it,
                (configuration.logPings).toByte()
            ).toBoolean()
        }
        val nameFn: (String) -> String = { it }

        return sendPingsGeneric(pingNames, sendPing, nameFn)
    }

    /**
     * Should be called from all users of the Glean testing API.
     *
     * This makes all asynchronous work synchronous so we can test the results of the
     * API synchronously.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun enableTestingMode() {
        @Suppress("EXPERIMENTAL_API_USAGE")
        Dispatchers.API.setTestingMode(enabled = true)
    }

    /**
     * Test-only method to destroy the owned glean-core handle.
     */
    internal fun testDestroyGleanHandle() {
        if (!isInitialized()) {
            // We don't need to destroy the Glean handle: it wasn't initialized.
            return
        }

        val e = RustError.ByReference()
        LibGleanFFI.INSTANCE.glean_destroy_glean(handle, e)
        handle = 0L
    }

    /**
     * Register a [PingType] in the registry associated with this [Glean] object.
     */
    @Synchronized
    internal fun registerPingType(pingType: PingType) {
        if (!this.isInitialized()) {
            pingTypeQueue.add(pingType)
        } else {
            LibGleanFFI.INSTANCE.glean_register_ping_type(
                handle,
                pingType.handle
            )
        }
    }

    /**
     * Returns true if a ping by this name is in the ping registry.
     *
     * For internal testing only.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun testHasPingType(pingName: String): Boolean {
        return LibGleanFFI.INSTANCE.glean_test_has_ping_type(handle, pingName).toBoolean()
    }
}

object Glean : GleanInternalAPI()
