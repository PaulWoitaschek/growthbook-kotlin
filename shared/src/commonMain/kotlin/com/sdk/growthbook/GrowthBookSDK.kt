package com.sdk.growthbook

import com.sdk.growthbook.sandbox.SandboxFileManager
import com.sdk.growthbook.Configs.ConfigsFlowDelegate
import com.sdk.growthbook.Configs.ConfigsViewModel
import com.sdk.growthbook.Features.FeaturesFlowDelegate
import com.sdk.growthbook.Features.FeaturesViewModel
import com.sdk.growthbook.Utils.*
import com.sdk.growthbook.Utils.Constants
import com.sdk.growthbook.Utils.FNV
import com.sdk.growthbook.model.*
import io.ktor.http.*


/*
    SDKBuilder - to pass APIKey, HostURL, UserAttributes, QAMode, Enabled
 */
class GBSDKBuilder(
    val apiKey: String,
    val hostURL: String,
    val attributes: HashMap<String, Any>,
    val appInstance : SandboxFileManager,
    val trackingCallback : (GBExperiment, GBExperimentResult) -> Unit
) {

    var qaMode: Boolean = false;
    var enabled: Boolean = true;
    var refreshHandler : GBCacheRefreshHandler? = null

    fun setQAMode(isEnabled : Boolean) : GBSDKBuilder {
        this.qaMode = isEnabled
        return this
    }

    fun setEnabled(isEnabled : Boolean) : GBSDKBuilder {
        this.enabled = isEnabled
        return this
    }

    fun setRefreshHandler(refreshHandler : GBCacheRefreshHandler) : GBSDKBuilder{
        this.refreshHandler = refreshHandler
        return this
    }

    fun initialize() : GrowthBookSDK{

        val gbContext = GBContext(apiKey = apiKey, enabled = enabled, attributes = attributes, url = hostURL, qaMode = qaMode, trackingCallback = trackingCallback)

        val sdkInstance = GrowthBookSDK(gbContext, appInstance, refreshHandler)

        return sdkInstance

    }
}

/*
    The main export of the libraries is a simple GrowthBook wrapper class that takes a Context object in the constructor.

    It exposes two main methods: feature and run.
 */

class GrowthBookSDK() : ConfigsFlowDelegate, FeaturesFlowDelegate {

    private val configVM = ConfigsViewModel(this)
    private val featureVM = FeaturesViewModel(this)

    private var refreshHandler : GBCacheRefreshHandler? = null

    internal companion object {
        lateinit var gbContext: GBContext
        lateinit var appInstance: SandboxFileManager
    }

    internal constructor(context : GBContext, instance : SandboxFileManager, refreshHandler : GBCacheRefreshHandler?) : this(){
        gbContext = context
        appInstance = instance
        this.refreshHandler = refreshHandler

        refreshCache()
    }

    fun refreshCache(){
        configVM.fetchConfigs()
        featureVM.fetchFeatures()
    }

    fun getGBContext() : GBContext {
        return gbContext
    }

    fun getOverrides() : GBOverrides {
        return gbContext.overrides
    }

    fun getFeatures() : GBFeatures {
        return gbContext.features
    }

    override fun configsFetchedSuccessfully(configs: GBOverrides, isRemote: Boolean) {
        gbContext.overrides = configs
        if (isRemote) {
            this.refreshHandler?.let { it(true) }
        }
    }

    override fun configsFetchFailed(error: GBError, isRemote: Boolean) {
        if (isRemote) {
            this.refreshHandler?.let { it(false) }
        }
    }

    override fun featuresFetchedSuccessfully(features : GBFeatures, isRemote: Boolean) {
        gbContext.features = features
        if (isRemote) {
            this.refreshHandler?.let { it(true) }
        }
    }

    override fun featuresFetchFailed(error: GBError, isRemote: Boolean) {

        if (isRemote) {
            this.refreshHandler?.let { it(false) }
        }

    }

    /*
    The feature method takes a single string argument, which is the unique identifier for the feature and returns a FeatureResult object.
     */
    fun feature(id: String) : GBFeatureResult {

        try {
            val targetFeature : GBFeature = gbContext.features.getValue(id)

            /// Loop through the feature rules (if any)
            val rules = targetFeature.rules
            if (rules != null && rules.size > 0) {

                for (rule in rules) {

                    /// TODO If the rule has a condition and it evaluates to false, skip this rule and continue to the next one


                    /// If rule.force is set
                    if (rule.force != null) {
                        /// If rule.coverage is set
                        if (rule.coverage != null){

                            /// Get the user hash value (context.attributes[experiment.hashAttribute || "id"]) and if empty, skip the rule
                                /// TODO check for experiment too
                            val attributeValue = gbContext.attributes.get(Constants.idAttributeKey) as? String ?: ""
                            if (attributeValue.isEmpty())
                                continue
                            else {
                                /// Compute a hash using the Fowler–Noll–Vo algorithm (specifically fnv32-1a)
                                val hashFNV =
                                    (FNV().fnv1a_32(attributeValue + id)?.rem(1000))?.div(1000);
                                /// If the hash is greater than rule.coverage, skip the rule
                                if (hashFNV != null && hashFNV > rule.coverage) {
                                    continue
                                }
                            }

                        }

                        /// Return (value = forced value, source = force)
                        return GBFeatureResult(value = rule.force, source = GBFeatureSource.force)

                    } else {
                        /// Otherwise, convert the rule to an Experiment object
                        val exp = GBExperiment(rule.key ?: id,
                            variations = rule.variations,
                            coverage = rule.coverage,
                            weights = rule.weights,
                            hashAttribute = rule.hashAttribute,
                            namespace = rule.namespace)

                        /// Run the experiment.
                        val result = run(exp)
                        if (result.inExperiment) {
                            return GBFeatureResult(value = result.value, source = GBFeatureSource.experiment)
                        } else {
                            /// If result.inExperiment is false, skip this rule and continue to the next one.
                            continue
                        }
                    }

                }

            }

            /// Return (value = defaultValue or null, source = defaultValue)
            return GBFeatureResult(value = targetFeature.defaultValue, source = GBFeatureSource.defaultValue)


        } catch (exception : Exception) {
            /// If the key doesn't exist in context.features, return immediately (value = null, source = unknownFeature).
            return GBFeatureResult(value = null, source = GBFeatureSource.unknownFeature)
        }

    }

    /*
    The run method takes an Experiment object and returns an ExperimentResult
     */
    fun run(experiment: GBExperiment) : GBExperimentResult {

        /// If experiment.variations has fewer than 2 variations, return immediately (not in experiment, variationId 0)
        //
        // If context.enabled is false, return immediately (not in experiment, variationId 0)
        if (experiment.variations.size < 2 || !gbContext.enabled ) {
            return GBExperimentResult(inExperiment = false, variationId = 0)
        }

        /// If context.url contains a querystring {experiment.trackingKey}=[0-9]+, return immediately (not in experiment, variationId from querystring)
        val url = Url(gbContext.url)

        if (url.parameters.contains(experiment.trackingKey)) {
            val value = url.parameters.get(experiment.trackingKey)?.toIntOrNull()
            if (value != null)
                return GBExperimentResult(inExperiment = false, variationId = value)
        }

        /// If context.forcedVariations[experiment.trackingKey] is defined, return immediately (not in experiment, forced variation)
        val forcedVariation = gbContext.forcedVariations.get(experiment.trackingKey)
        if (forcedVariation != null) {
            return GBExperimentResult(inExperiment = false, variationId = forcedVariation)
        }

        /// If context.overrides[experiment.trackingKey] is set, merge override properties into the experiment
        if (gbContext.overrides.containsKey(experiment.trackingKey)) {
            val overrideExp = gbContext.overrides.getValue(experiment.trackingKey)

            experiment.weights = overrideExp.weights
            experiment.active = overrideExp.status == GBExperimentStatus.running
            experiment.coverage = overrideExp.coverage
            experiment.force = overrideExp.force
        }

        /// If experiment.action is set to false, return immediately (not in experiment, variationId 0)
        if (!(experiment.active ?: false)) {
            return GBExperimentResult(inExperiment = false, variationId = 0)
        }

        /// Get the user hash attribute and value (context.attributes[experiment.hashAttribute || "id"]) and if empty, return immediately (not in experiment, variationId 0)
        val id = gbContext.attributes.get(Constants.idAttributeKey) as? String ?: ""
        val attributeValue = gbContext.attributes.get(experiment.hashAttribute) as? String ?: id
        if (attributeValue.isEmpty()) {
            return GBExperimentResult(inExperiment = false, variationId = 0)
        }

        /// If experiment.namespace is set, check if hash value is included in the range and if not, return immediately (not in experiment, variationId 0)
        val hashValue = attributeValue.toIntOrNull()
        if (experiment.namespace != null && hashValue != null && (experiment.namespace.rangeStart > hashValue || hashValue > experiment.namespace.rangeEnd)) {
            return GBExperimentResult(inExperiment = false, variationId = 0)
        }

        /// If experiment.include is set, call the function and if "false" is returned or it throws, return immediately (not in experiment, variationId 0)
        val include = experiment.include
        if (include != null) {
            try {
                if (!include()) {
                    return GBExperimentResult(inExperiment = false, variationId = 0)
                }
            } catch (exception : Exception) {
                return GBExperimentResult(inExperiment = false, variationId = 0)
            }
        }

        /// TODO If experiment.condition is set and the condition evaluates to false, return immediately (not in experiment, variationId 0)
        if (experiment.condition != null) {
            return GBExperimentResult(inExperiment = false, variationId = 0)
        }


        /// Default variation weights and coverage if not specified
        var weights = experiment.weights;
        if (weights == null) {
            // Default weights to an even split between all variations
            experiment.weights = List(experiment.variations.size){1.0f / (experiment.variations.size)}

        }
        /// Default coverage to 1 (100%)
        val coverage = experiment.coverage ?: 1.0f;
        experiment.coverage = coverage

        /// Calculate bucket ranges for the variations
        // Convert weights/coverage to ranges
// 50/50 split at 100% coverage == [[0, 0.5], [0.5, 1]]
// 20/80 split with 50% coverage == [[0, 0.1], [0.2, 0.6]]
        var cumulative = 0f

        val ranges = experiment.weights?.map { weight ->
            val start = cumulative
            cumulative += weight
            GBNameSpace("", start, start + (coverage * weight))
        }

        /// Compute a hash using the Fowler–Noll–Vo algorithm (specifically fnv32-1a) and assign a variation
        val hashFNV =
            (FNV().fnv1a_32(id + experiment.trackingKey)?.rem(1000))?.div(1000);

        var assigned = -1;
        if (hashFNV != null && ranges != null) {
            var counter = 0
            ranges.forEach { range ->
                if (hashFNV >= range.rangeStart && hashFNV < range.rangeEnd) {
                    assigned = counter
                }
                counter++
            }
        }


        /// If not assigned a variation (assigned === -1), return immediately (not in experiment, variationId 0)
        if (assigned == -1) {
            return GBExperimentResult(inExperiment = false, variationId = 0)
        }

        /// If experiment.force is set, return immediately (not in experiment, variationId experiment.force)
        val forceExp = experiment.force
        if (forceExp != null) {
            return GBExperimentResult(inExperiment = false, variationId = forceExp)
        }

        /// If context.qaMode is true, return immediately (not in experiment, variationId 0)
        if (gbContext.qaMode) {
            return GBExperimentResult(inExperiment = false, variationId = 0)
        }

        /// Fire context.trackingCallback if set and the combination of hashAttribute, hashValue, experiment.key, and variationId has not been tracked before

        val result = GBExperimentResult(inExperiment = true, variationId = assigned, value = experiment.variations.get(assigned))
        gbContext.trackingCallback(experiment, result)

        /// Return (in experiment, assigned variation)
        return result
    }
}