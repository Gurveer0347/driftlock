package org.driftlock.app

import androidx.compose.animation.AnimatedVisibility
import `in`.driftlock.ui.design.GlassSurface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

@Composable fun ResearchPanel(state:NativeState) {
    val context=LocalContext.current
    val evidence=remember(context){runCatching{
        val replay=context.assets.open("research/validation_replay.json").bufferedReader().use{it.readText()}
        val metrics=context.assets.open("research/validation_metrics.json").bufferedReader().use{it.readText()}
        ResearchEvidence.parse(replay,metrics)
    }}
    if(evidence.isFailure){
        CardBody("Research evidence unavailable","The bundled validation evidence failed to load. No result is shown.")
        return
    }
    val data=evidence.getOrThrow()
    var details by rememberSaveable{mutableStateOf(false)}
    CardBody("Model lab","Six recorded phone-motion channels feed a compact CNN + GRU. The model predicts the dataset's recorded GPS-speed number and a spread around that estimate.")
    GlassSurface(tint=Color(0xBBFFF2E2),shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("RECORDED-REFERENCE EXPERIMENT",style=MaterialTheme.typography.labelMedium,color=Color(0xFF875727))
            Text("Trained on official smartphone recordings",style=MaterialTheme.typography.titleMedium)
            Text("Separate from navigation · Android model calls: ${state.sensor.model.invocations}",style=MaterialTheme.typography.bodyMedium)
        }
    }
    Text("Trained model on held-out validation recording",style=MaterialTheme.typography.titleMedium)
    ResearchChart(data.points)
    Text("Green: model prediction   ·   Grey: recorded reference",style=MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Column {Text("Validation RMSE",style=MaterialTheme.typography.labelMedium);Text("%.5f".format(data.rmse),style=MaterialTheme.typography.headlineSmall)}
        Column {Text("Windows",style=MaterialTheme.typography.labelMedium);Text("%,d".format(data.windows),style=MaterialTheme.typography.headlineSmall)}
    }
    Text("The source has not established this label's units, timing or travel direction. RMSE is in recorded reference units; it does not measure navigation accuracy.",style=MaterialTheme.typography.bodyMedium)
    CardBody("The simple baseline did better","Across ${data.heldCutCases} nominal 30/60-second held-cut cases, model mean-case RMSE was %.5f; holding the last recorded pre-cut value was %.5f. Lower is better. These cuts are diagnostics, not measured real GNSS outages.".format(data.modelHeldCutRmse,data.holdLastRmse))
    CardBody("What sigma means","The model's sigma estimates standard deviation of its error against the recorded reference number, in the same unresolved units. It is not map-position uncertainty or navigation confidence.")
    TextButton(onClick={details=!details}){Text(if(details)"Hide model details" else "Show model details")}
    AnimatedVisibility(details){CardBody("Evidence and limits","Official IO-VNBD smartphone recordings · validation split · 40 IMU samples at 10 Hz · ax, ay, az, gx, gy, gz · compact 1D CNN + 32-state GRU. GPS speed is a label, never an input. Source axes/frame, label unit, fix freshness, timing and reverse-direction meaning need verification. Checkpoint SHA-256: ${data.checkpointSha256}. Final test remains reserved. No Android inference parity is claimed.")}
}

@Composable private fun ResearchChart(points:List<ResearchPoint>) {
    val values=points.flatMap{listOf(it.prediction,it.reference)}
    val low=values.minOrNull() ?: 0.0;val high=values.maxOrNull() ?: 1.0
    val span=max(1.0,high-low)
    Canvas(Modifier.fillMaxWidth().height(190.dp)) {
        val left=6f;val right=size.width-6f;val top=12f;val bottom=size.height-12f
        for(i in 0..3) {
            val y=top+(bottom-top)*i/3
            drawLine(Color(0xFFDCE4DA),Offset(left,y),Offset(right,y),1f)
        }
        fun trace(select:(ResearchPoint)->Double):Path {
            val path=Path()
            points.forEachIndexed{i,p->
                val x=left+(right-left)*i/max(1,points.lastIndex)
                val y=bottom-((select(p)-low)/span*(bottom-top)).toFloat()
                if(i==0)path.moveTo(x,y) else path.lineTo(x,y)
            }
            return path
        }
        drawPath(trace{it.reference},Color(0xFF7B8980),style=Stroke(width=2f))
        drawPath(trace{it.prediction},Color(0xFF167548),style=Stroke(width=3f))
    }
}
