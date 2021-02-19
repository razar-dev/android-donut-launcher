package ussr.razar.android.content

import android.content.Intent

object LauncherIntent : Intent() {
    /**
     *
     */
    const val PNAME: String = "ussr.razar.android.hpp."

    object Notification {
        const val NOTIFICATION_IN_VIEWPORT: String = PNAME + "NOTIFICATION_IN_VIEWPORT"
        const val NOTIFICATION_OUT_VIEWPORT: String = PNAME + "NOTIFICATION_OUT_VIEWPORT"
    }

    object Action {
        const val ACTION_READY: String = PNAME + "ACTION_READY"
    }


    object Extra {
        const val EXTRA_APPWIDGET_ID: String = PNAME + "EXTRA_APPWIDGET_ID"
        const val EXTRA_API_VERSION: String = PNAME + "EXTRA_API_VERSION"

    }
}