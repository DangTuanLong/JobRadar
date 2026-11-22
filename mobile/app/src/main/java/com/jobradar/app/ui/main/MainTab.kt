package com.jobradar.app.ui.main

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.ui.graphics.vector.ImageVector
import com.jobradar.app.R
import com.jobradar.app.ui.navigation.ProfileTabRoute
import com.jobradar.app.ui.navigation.ScanCvTabRoute
import com.jobradar.app.ui.navigation.SuggestionsTabRoute

enum class MainTab(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val route: Any,
) {
    SCAN_CV(
        labelRes = R.string.tab_scan_cv,
        icon = Icons.Outlined.DocumentScanner,
        route = ScanCvTabRoute,
    ),
    SUGGESTIONS(
        labelRes = R.string.tab_suggestions,
        icon = Icons.Outlined.TravelExplore,
        route = SuggestionsTabRoute,
    ),
    PROFILE(
        labelRes = R.string.tab_profile,
        icon = Icons.Outlined.PersonOutline,
        route = ProfileTabRoute,
    ),
}
