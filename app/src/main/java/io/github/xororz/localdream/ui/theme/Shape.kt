package io.github.xororz.localdream.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Restrained radii keep expressive components modern without turning every control into a pill.
// extraSmall -> chip/snackbar, small -> text field/menu, medium -> card,
// large -> FAB/nav drawer, extraLarge -> dialog/bottom sheet.
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
