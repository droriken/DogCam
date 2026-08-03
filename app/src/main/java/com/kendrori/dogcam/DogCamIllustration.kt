package com.kendrori.dogcam

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun DogCamIllustration(
    modifier: Modifier = Modifier
) {
    val illustrationDescription = stringResource(R.string.content_description_dog_illustration)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .semantics { contentDescription = illustrationDescription },
        contentAlignment = Alignment.Center
    ) {
        // Adaptive icons can't be loaded directly by painterResource if they are <adaptive-icon>
        // We manually layer the background and foreground here.
        Image(
            painter = painterResource(id = R.drawable.ic_dogcam_icon_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        Image(
            painter = painterResource(id = R.mipmap.ic_dogcam_icon_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DogCamIllustrationPreview() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .size(400.dp)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            DogCamIllustration(modifier = Modifier.size(360.dp))
        }
    }
}
