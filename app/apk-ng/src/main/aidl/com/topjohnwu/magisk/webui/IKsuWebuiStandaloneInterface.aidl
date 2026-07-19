package com.topjohnwu.magisk.webui;

import android.content.pm.PackageInfo;
import rikka.parcelablelist.ParcelableListSlice;

interface IKsuWebuiStandaloneInterface {
    ParcelableListSlice<PackageInfo> getPackages(int flags);
}
