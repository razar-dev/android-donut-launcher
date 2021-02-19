package ussr.razar.android.internal.utils

import ussr.razar.android.dount.launcher.ApplicationInfo
import java.text.Collator
import java.util.*

class ApplicationInfoComparator : Comparator< ApplicationInfo> {
    override fun compare(a: ApplicationInfo, b: ApplicationInfo): Int {
        return Collator.getInstance().compare(a.title?.toString(), b.title?.toString())
    }
}