/**
 * Pokemon Go Bot  Copyright (C) 2016  PokemonGoBot-authors (see authors.md for more information)
 * This program comes with ABSOLUTELY NO WARRANTY;
 * This is free software, and you are welcome to redistribute it under certain conditions.
 *
 * For more information, refer to the LICENSE file in this repositories root directory
 */

package ink.abb.pogo.scraper.util.pokemon

import POGOProtos.Data.PokemonDataOuterClass.PokemonData
import POGOProtos.Enums.PokemonIdOuterClass
import ink.abb.pogo.api.PoGoApi
import ink.abb.pogo.api.util.PokemonCpUtils
import ink.abb.pogo.api.util.PokemonMeta
import ink.abb.pogo.api.util.PokemonMetaRegistry
import ink.abb.pogo.scraper.Settings
import java.util.concurrent.atomic.AtomicInteger

fun PokemonData.getIv(): Int {
    val iv = individualAttack + individualDefense + individualStamina
    return iv
}

fun PokemonData.getIvPercentage(): Int {
    val iv = getIv()
    val ivPercentage = (iv * 100) / 45
    return ivPercentage
}

//fun PokemonData.getCpPercentageToPlayer(playerlevel: Int): Int {
//    // TODO replace this when api has implemented this see Pokemon.kt
//    return -1
//}

fun PokemonData.getStatsFormatted(): String {
    val details = "Stamina: $individualStamina | Attack: $individualAttack | Defense: $individualDefense"
    return details + " | IV: ${getIv()} (${(getIvPercentage())}%)"
}

val PokemonData.maxCpForPlayer: Int
        // TODO!!!
    get() = 0

fun PokemonData.getCpPercentageToPlayer(): Int {
    return (cp.toDouble() / maxCpForPlayer.toDouble() * 100).toInt()
}

fun PokemonData.shouldTransfer(settings: Settings, pokemonCounts: MutableMap<String, Int>, candies: AtomicInteger): Pair<Boolean, String> {
    val obligatoryTransfer = settings.obligatoryTransfer
    val ignoredPokemon = settings.ignoredPokemon
    val ivPercentage = getIvPercentage()
    val minIVPercentage = settings.transferIvThreshold
    val minCP = settings.transferCpThreshold
    val minCpPercentage = settings.transferCpMinThreshold

    var shouldRelease = obligatoryTransfer.contains(this.pokemonId)
    var reason: String = "Obligatory transfer"
    if (!ignoredPokemon.contains(this.pokemonId)) {
        // shouldn't release? check for IV/CP
        if (!shouldRelease) {
            var ivTooLow = false
            var cpTooLow = false
            var maxCpInRange = false

            // never transfer > min IV percentage (unless set to -1)
            if (ivPercentage < minIVPercentage || minIVPercentage == -1) {
                ivTooLow = true
            }
            // never transfer > min CP  (unless set to -1)
            if (this.cp < minCP || minCP == -1) {
                cpTooLow = true
            }
            reason = "CP < $minCP and IV < $minIVPercentage%"

            if (minCpPercentage != -1 && minCpPercentage >= getCpPercentageToPlayer()) {
                maxCpInRange = true
                reason += " and CP max $maxCpForPlayer: achieved ${getCpPercentageToPlayer()}% <= $minCpPercentage%"
            }
            shouldRelease = ivTooLow && cpTooLow && (maxCpInRange || minCpPercentage == -1)
        }

        // still shouldn't release? Check if we have too many
        val max = settings.maxPokemonAmount
        val name = this.pokemonId.name
        val count = pokemonCounts.getOrElse(name, { 0 }) + 1
        pokemonCounts.put(name, count)

        if (!shouldRelease && max != -1 && count > max) {
            shouldRelease = true
            reason = "Too many"
        }
        // Save pokemon for evolve stacking
        val candyToEvolve = PokemonMetaRegistry.getMeta(this.pokemonId).candyToEvolve
        if (shouldRelease && settings.evolveBeforeTransfer.contains(this.pokemonId) && settings.evolveStackLimit > 0) {
            val maxToMaintain = candies.get() / candyToEvolve;
            if (candyToEvolve > 0 && count > maxToMaintain) {
                shouldRelease = true
                reason = "Not enough candy ${candies.get()}/$candyToEvolve: max $maxToMaintain"
            } else {
                shouldRelease = false
            }
        }
    }
    return Pair(shouldRelease, reason)
}

fun PokemonData.eggKmWalked(poGoApi: PoGoApi): Double {
    if (!incubated) {
        return 0.0
    }
    val incubators = poGoApi.inventory.eggIncubators.filter {
        it.id == eggIncubatorId
    }

    if (incubators.isNotEmpty()) {
        val incubator = incubators.first()
        return eggKmWalkedTarget - (incubator.targetKmWalked - poGoApi.inventory.playerStats.kmWalked)
    } else {
        return 0.0
    }
}

val PokemonData.incubated: Boolean
    get() {
        return eggIncubatorId.isNotBlank()
    }

val PokemonData.injured: Boolean
    get() {
        return !fainted && stamina < staminaMax
    }

val PokemonData.fainted: Boolean
    get() {
        return stamina == 0
    }

val PokemonData.meta: PokemonMeta
    get() = PokemonMetaRegistry.getMeta(this.getPokemonId())

val PokemonData.maxCp: Int
    get() {
        val pokemonMeta = meta
        val attack = getIndividualAttack() + pokemonMeta.baseAttack
        val defense = getIndividualDefense() + pokemonMeta.baseDefense
        val stamina = getIndividualStamina() + pokemonMeta.baseStamina
        return PokemonCpUtils.getMaxCp(attack, defense, stamina)
    }

fun PokemonData.getMaxCpForLevel(level: Int): Int {
    val pokemonMeta = meta
    val attack = getIndividualAttack() + pokemonMeta.baseAttack
    val defense = getIndividualDefense() + pokemonMeta.baseDefense
    val stamina = getIndividualStamina() + pokemonMeta.baseStamina
    val playerLevel = level
    return PokemonCpUtils.getMaxCpForPlayer(attack, defense, stamina, playerLevel)
}

val PokemonData.absoluteMaxCp: Int
    get() = PokemonCpUtils.getAbsoluteMaxCp(pokemonId)

val PokemonData.cpFullEvolveAndPowerup: Int
    get() = getMaxCpFullEvolveAndPowerup(40)

fun PokemonData.getMaxCpFullEvolveAndPowerupForLevel(level: Int): Int {
    return getMaxCpFullEvolveAndPowerup(level)
}

private fun PokemonData.getMaxCpFullEvolveAndPowerup(playerLevel: Int): Int {
    val highestUpgradedFamily: PokemonIdOuterClass.PokemonId
    if (arrayListOf(PokemonIdOuterClass.PokemonId.VAPOREON, PokemonIdOuterClass.PokemonId.JOLTEON, PokemonIdOuterClass.PokemonId.FLAREON).contains(pokemonId)) {
        highestUpgradedFamily = pokemonId
    } else if (pokemonId === PokemonIdOuterClass.PokemonId.EEVEE) {
        highestUpgradedFamily = PokemonIdOuterClass.PokemonId.FLAREON
    } else {
        highestUpgradedFamily = PokemonMetaRegistry.getHighestForFamily(meta.family)
    }
    val pokemonMeta = PokemonMetaRegistry.getMeta(highestUpgradedFamily)
    val attack = individualAttack + pokemonMeta.baseAttack
    val defense = individualDefense + pokemonMeta.baseDefense
    val stamina = individualStamina + pokemonMeta.baseStamina
    return PokemonCpUtils.getMaxCpForPlayer(attack, defense, stamina, playerLevel)
}

val PokemonData.combinedCpMultiplier: Float
    get() = cpMultiplier + additionalCpMultiplier

val PokemonData.cpAfterEvolve: Int
    get() {
        if (arrayListOf(PokemonIdOuterClass.PokemonId.VAPOREON, PokemonIdOuterClass.PokemonId.JOLTEON, PokemonIdOuterClass.PokemonId.FLAREON).contains(pokemonId)) {
            return cp
        }
        val highestUpgradedFamily = PokemonMetaRegistry.getHighestForFamily(meta.family)
        if (pokemonId === highestUpgradedFamily) {
            return cp
        }
        var pokemonMeta = PokemonMetaRegistry.getMeta(highestUpgradedFamily)
        val secondHighest = pokemonMeta.parentId
        if (getPokemonId() === secondHighest) {
            val attack = individualAttack + pokemonMeta.baseAttack
            val defense = individualDefense + pokemonMeta.baseDefense
            val stamina = individualStamina + pokemonMeta.baseStamina
            return PokemonCpUtils.getCp(attack, defense, stamina, combinedCpMultiplier)
        }
        pokemonMeta = PokemonMetaRegistry.getMeta(secondHighest)
        val attack = individualAttack + pokemonMeta.baseAttack
        val defense = individualDefense + pokemonMeta.baseDefense
        val stamina = individualStamina + pokemonMeta.baseStamina
        return PokemonCpUtils.getCp(attack, defense, stamina, combinedCpMultiplier)
    }

val PokemonData.cpAfterFullEvolve: Int
    get() {
        if (arrayListOf(PokemonIdOuterClass.PokemonId.VAPOREON, PokemonIdOuterClass.PokemonId.JOLTEON, PokemonIdOuterClass.PokemonId.FLAREON).contains(pokemonId)) {
            return cp
        }
        val highestUpgradedFamily = PokemonMetaRegistry.getHighestForFamily(meta.family)
        if (pokemonId === highestUpgradedFamily) {
            return cp
        }

        val pokemonMeta = PokemonMetaRegistry.getMeta(highestUpgradedFamily)
        val attack = individualAttack + pokemonMeta.baseAttack
        val defense = individualDefense + pokemonMeta.baseDefense
        val stamina = individualStamina + pokemonMeta.baseStamina
        return PokemonCpUtils.getCp(attack, defense, stamina, combinedCpMultiplier)
    }

val PokemonData.cpAfterPowerup: Int
    get() = PokemonCpUtils.getCpAfterPowerup(cp, combinedCpMultiplier)

val PokemonData.candyCostsForPowerup: Int
    get() = PokemonCpUtils.getCandyCostsForPowerup(combinedCpMultiplier, numUpgrades)

val PokemonData.stardustCostsForPowerup: Int
    get() = PokemonCpUtils.getStartdustCostsForPowerup(combinedCpMultiplier, numUpgrades)
