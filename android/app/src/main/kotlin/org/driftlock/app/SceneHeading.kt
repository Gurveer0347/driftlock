package org.driftlock.app
import kotlin.math.*
internal fun nearestSceneHeading(current:Double,target:Double):Double = current+atan2(sin(target-current),cos(target-current))
