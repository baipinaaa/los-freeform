package com.xiaochuang.freeform.xposed;

import com.xiaochuang.freeform.common.model.AppInfo;

interface IAppListCallback {
    void onAppListReceived(in List<AppInfo> appList);
    void onAppListFinished();
}