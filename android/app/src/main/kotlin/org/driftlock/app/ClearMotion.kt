package org.driftlock.app

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalJourneyShared=staticCompositionLocalOf<SharedTransitionScope?>{null}
internal val LocalJourneyMotion=staticCompositionLocalOf<AnimatedVisibilityScope?>{null}

/** Copy-only mockup mode; it never changes sensor provenance or navigation. */
internal val LocalRecordedTripPreview=staticCompositionLocalOf{false}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable internal fun Modifier.journeyBounds():Modifier {
    val shared=LocalJourneyShared.current ?: return this
    val motion=LocalJourneyMotion.current ?: return this
    return with(shared) {
        this@journeyBounds.sharedBounds(rememberSharedContentState("journey-region"),motion,
            boundsTransform={_,_->tween(650,easing=FastOutSlowInEasing)},
            enter=fadeIn(tween(420)),exit=fadeOut(tween(240)))
    }
}

@Composable internal fun GlassButton(
    onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    colors:ButtonColors=ButtonDefaults.buttonColors(containerColor=Color(0xDC163F32)),
    content:@Composable RowScope.()->Unit,
) {
    val interaction=remember{MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    val scale=animateFloatAsState(if(pressed).975f else 1f,tween(140),label="glass-press")
    val tint=if(enabled)colors.containerColor else colors.disabledContainerColor
    val shape=ButtonDefaults.shape
    Button(onClick,modifier.graphicsLayer{scaleX=scale.value;scaleY=scale.value}.clip(shape)
        .background(tint),enabled,
        colors=colors.copy(containerColor=Color.Transparent,disabledContainerColor=Color.Transparent),
        interactionSource=interaction,content=content)
}

@Composable internal fun GlassOutlinedButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable RowScope.()->Unit) {
    val interaction=remember{MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    val scale=animateFloatAsState(if(pressed).98f else 1f,tween(140),label="glass-outline-press")
    OutlinedButton(onClick,modifier.graphicsLayer{scaleX=scale.value;scaleY=scale.value},enabled,
        colors=ButtonDefaults.outlinedButtonColors(containerColor=Color.White.copy(alpha=.46f)),interactionSource=interaction,content=content)
}
