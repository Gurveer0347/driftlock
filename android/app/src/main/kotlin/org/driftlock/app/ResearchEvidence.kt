package org.driftlock.app

import org.json.JSONObject

data class ResearchPoint(val t:Double,val prediction:Double,val reference:Double,val sigma:Double)
data class ResearchEvidence(
    val points:List<ResearchPoint>,val windows:Int,val rmse:Double,val mae:Double,
    val modelHeldCutRmse:Double,val holdLastRmse:Double,val heldCutCases:Int,
    val checkpointSha256:String,val validForNavigation:Boolean=false,
) {
    companion object {
        fun parse(replayText:String,metricsText:String):ResearchEvidence {
            val replay=JSONObject(replayText);val metrics=JSONObject(metricsText)
            val metadata=replay.getJSONObject("metadata")
            require(replay.getString("kind")=="research" && replay.getJSONObject("units").getString("speed")=="recorded units")
            require(metadata.getString("split")=="val" && !metadata.getBoolean("valid_for_navigation"))
            require(metrics.getString("split")=="val" && !metrics.getBoolean("valid_for_navigation") && !metrics.getBoolean("final_test_evaluated"))
            require(metrics.getString("research_contract")=="driftlock-iovnbd-recorded-reference-v1")
            require(metadata.getString("manifest_sha256")==metrics.getString("manifest_sha256"))
            val rows=replay.getJSONArray("frames")
            val points=(0 until rows.length()).map{i->
                val row=rows.getJSONObject(i)
                ResearchPoint(row.getDouble("target_t"),row.getDouble("speed"),row.getDouble("reference"),row.getDouble("sigma"))
            }
            require(points.isNotEmpty() && points.all{listOf(it.t,it.prediction,it.reference,it.sigma).all(Double::isFinite) && it.sigma>=0})
            val full=metrics.getJSONObject("all_windows")
            val held=metrics.getJSONObject("held_reference_baselines")
            val cnn=held.getJSONObject("cnn_gru_delayed_centre")
            val last=held.getJSONObject("hold_last_recorded_pre_cut")
            require(cnn.getInt("cases")==last.getInt("cases"))
            return ResearchEvidence(points,full.getInt("n"),full.getDouble("rmse_reference_units"),full.getDouble("mae_reference_units"),
                cnn.getDouble("mean_case_rmse_reference_units"),last.getDouble("mean_case_rmse_reference_units"),cnn.getInt("cases"),
                metrics.getString("checkpoint_sha256"))
        }
    }
}
