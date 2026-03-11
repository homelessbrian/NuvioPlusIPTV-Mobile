package com.nuvio.app.features.home

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.catalog.supportsPagination

data class HomeCatalogDefinition(
    val key: String,
    val defaultTitle: String,
    val addonName: String,
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val supportsPagination: Boolean,
)

fun buildHomeCatalogDefinitions(addons: List<ManagedAddon>): List<HomeCatalogDefinition> =
    addons.mapNotNull { addon ->
        val manifest = addon.manifest ?: return@mapNotNull null
        addon to manifest
    }.flatMap { (addon, manifest) ->
        manifest.catalogs
            .filter { catalog -> catalog.extra.none { it.isRequired } }
            .map { catalog ->
                HomeCatalogDefinition(
                    key = "${manifest.id}:${catalog.type}:${catalog.id}",
                    defaultTitle = "${catalog.name} - ${catalog.type.displayLabel()}",
                    addonName = manifest.name,
                    manifestUrl = addon.manifestUrl,
                    type = catalog.type,
                    catalogId = catalog.id,
                    supportsPagination = catalog.supportsPagination(),
                )
            }
    }

internal fun String.displayLabel(): String =
    replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase() else char.toString()
    }
