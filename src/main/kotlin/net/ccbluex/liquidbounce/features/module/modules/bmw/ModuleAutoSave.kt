package net.ccbluex.liquidbounce.features.module.modules.bmw

import net.ccbluex.liquidbounce.config.types.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.block.getBlock
import net.minecraft.util.math.BlockPos
import net.minecraft.fluid.FlowableFluid
import kotlin.math.floor
import kotlinx.coroutines.*

object ModuleAutoSave : ClientModule("AutoSave", Category.BMW) {
    private object AutoStuck : ToggleableConfigurable(ModuleAutoSave, "AutoStuck", true) {
        val stuckOnlyVoid by boolean("StuckOnlyVoid", true)
        val stuckFallDistance by int("StuckFallDistance", 5, 1..50, "blocks")
    }

    private object AutoScaffold : ToggleableConfigurable(ModuleAutoSave, "AutoScaffold", true) {
    val scaffoldOnlyVoid by boolean("ScaffoldOnlyVoid", true)
    val scaffoldVoidDistance by int("ScaffoldVoidDistance", 3, 1..50, "blocks")
    val hitsUntilActivate by intRange("HitsUntilActivate", 0..1, 0..3)
}

    init {
        tree(AutoStuck)
        tree(AutoScaffold)
    }

    private const val LOWEST_Y = -64
    private const val EDGE_THRESHOLD = 0.3
    private const val SCAFFOLD_DELAY_MS = 50L

    private var lastGroundY = LOWEST_Y
    private var stuckSaving = false
    private var scaffoldSaving = false
    private var receivedHits = 0
    private var limitUntilActivate = AutoScaffold.hitsUntilActivate.random()

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Suppress("unused")
    private val tickHandler = tickHandler {
        when {
            player.isOnGround -> updateGroundPosition()
            AutoStuck.enabled -> handleStuckProtection()
            AutoScaffold.enabled -> handleScaffold()
        }
    }

    private fun updateGroundPosition() {
        lastGroundY = player.blockY - 1
        if (scaffoldSaving) {
            deactivateScaffold()
        }
    }

    private fun handleStuckProtection() {
        val shouldTrigger = (!AutoStuck.stuckOnlyVoid || checkVoidPresence()) &&
                player.y <= lastGroundY + 1 - AutoStuck.stuckFallDistance

        when {
            shouldTrigger && !stuckSaving -> activateStuckProtection()
            !shouldTrigger && stuckSaving -> deactivateStuckProtection()
        }
    }

    private fun handleScaffold() {
        if (player.hurtTime in 1..9) receivedHits++

        val voidCheckParam = if (AutoScaffold.scaffoldOnlyVoid) -1 else AutoScaffold.scaffoldVoidDistance
        val currentInVoid = checkVoidPresence(voidCheckParam)
        val shouldActivate = receivedHits >= limitUntilActivate && currentInVoid

        when {
            shouldActivate && !scaffoldSaving -> activateScaffold()
            (!shouldActivate || !currentInVoid) && scaffoldSaving -> deactivateScaffold()
        }
    }

    private fun checkVoidPresence(voidDistance: Int = -1): Boolean {
        if (player.isOnGround || player.abilities.flying || player.y < LOWEST_Y + 2) return false

        val (x, y, z) = player.pos.run { Triple(x, y, z) }
        val minY = if (voidDistance == -1) LOWEST_Y else (y - voidDistance).toInt()
        val maxY = player.blockY - 1

        if (maxY < minY) return true

        val xFloor = floor(x)
        val zFloor = floor(z)
        val xRem = x - xFloor
        val zRem = z - zFloor


        if (checkColumn(xFloor.toInt(), zFloor.toInt(), minY, maxY)) return true

        if (xRem <= EDGE_THRESHOLD && checkColumn(xFloor.toInt() - 1, zFloor.toInt(), minY, maxY)) return true
        if ((1 - xRem) <= EDGE_THRESHOLD && checkColumn(xFloor.toInt() + 1, zFloor.toInt(), minY, maxY)) return true
        if (zRem <= EDGE_THRESHOLD && checkColumn(xFloor.toInt(), zFloor.toInt() - 1, minY, maxY)) return true
        if ((1 - zRem) <= EDGE_THRESHOLD && checkColumn(xFloor.toInt(), zFloor.toInt() + 1, minY, maxY)) return true

        if (xRem <= EDGE_THRESHOLD && zRem <= EDGE_THRESHOLD &&
            checkColumn(xFloor.toInt() - 1, zFloor.toInt() - 1, minY, maxY)) return true
        if ((1 - xRem) <= EDGE_THRESHOLD && (1 - zRem) <= EDGE_THRESHOLD &&
            checkColumn(xFloor.toInt() + 1, zFloor.toInt() + 1, minY, maxY)) return true
        if (xRem <= EDGE_THRESHOLD && (1 - zRem) <= EDGE_THRESHOLD &&
            checkColumn(xFloor.toInt() - 1, zFloor.toInt() + 1, minY, maxY)) return true
        if ((1 - xRem) <= EDGE_THRESHOLD && zRem <= EDGE_THRESHOLD &&
            checkColumn(xFloor.toInt() + 1, zFloor.toInt() - 1, minY, maxY)) return true

        return false
    }

    private fun checkColumn(x: Int, z: Int, minY: Int, maxY: Int): Boolean {
        for (y in maxY downTo minY) {
            BlockPos(x, y, z).getBlock()?.let { block ->
                if (block.defaultState.blocksMovement() || block is FlowableFluid) {
                    return false
                }
            } ?: break
        }
        return true
    }

    private fun activateStuckProtection() {
        ModuleStuck.enabled = true
        stuckSaving = true
    }

    private fun deactivateStuckProtection() {
        ModuleStuck.enabled = false
        stuckSaving = false
    }

private fun activateScaffold() {
    ModuleClutch.enabled = true
    scaffoldSaving = true
    coroutineScope.launch {
        delay(SCAFFOLD_DELAY_MS * 2)

        if (scaffoldSaving && !checkVoidPresence(
                if (AutoScaffold.scaffoldOnlyVoid) -1 else AutoScaffold.scaffoldVoidDistance
            )) {
            deactivateScaffold()
        }

        receivedHits = 0
        limitUntilActivate = AutoScaffold.hitsUntilActivate.random()
    }
}

private fun deactivateScaffold() {
    if (scaffoldSaving) {
        ModuleClutch.enabled = false
        scaffoldSaving = false
        receivedHits = 0
        limitUntilActivate = AutoScaffold.hitsUntilActivate.random()
    }
}

    override fun disable() {
    coroutineScope.cancel()
    resetState(true)
    super.disable()
}

private fun resetState(disableModules: Boolean) {
    lastGroundY = LOWEST_Y
    if (disableModules) {
        ModuleStuck.takeIf { stuckSaving }?.enabled = false
        ModuleClutch.takeIf { scaffoldSaving }?.enabled = false
    }
    stuckSaving = false
    scaffoldSaving = false
}

}
