package app.dibe.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityTest {
    private val pkg = "app.dibe.android"

    @Test
    fun acceptsOnlyOtherAppsContent() {
        assertTrue(isForeignContent("content", "com.android.providers.media.documents", pkg))
        assertTrue(isForeignContent("content", "media", pkg))
        assertTrue(isForeignContent("content", "app.dibe.androidx.files", pkg))
        assertFalse(isForeignContent("file", null, pkg))
        assertFalse(isForeignContent("file", "", pkg))
        assertFalse(isForeignContent("content", "app.dibe.android.files", pkg))
        assertFalse(isForeignContent("content", "APP.DIBE.ANDROID.FILES", pkg))
        assertFalse(isForeignContent("content", "app.dibe.android", pkg))
        assertFalse(isForeignContent("content", "app.dibe.android.androidx-startup", pkg))
        assertFalse(isForeignContent("content", null, pkg))
        assertFalse(isForeignContent("http", "example.com", pkg))
        assertFalse(isForeignContent(null, null, pkg))
    }
}
