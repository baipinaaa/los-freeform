// IYAMFManager.aidl
package com.xiaochuang.freeform.xposed;

import com.xiaochuang.freeform.xposed.IOpenCountListener;
import com.xiaochuang.freeform.common.model.AppInfo;
import com.xiaochuang.freeform.xposed.IAppListCallback;
import com.xiaochuang.freeform.xposed.IAppIconCallback;
// Declare any non-default types here with import statements

interface IYAMFManager {
    String getVersionName();

    int getVersionCode();

    int getUid();

    void createWindow();

    long getBuildTime();

    String getConfigJson();

    void updateConfig(String newConfig);

    void registerOpenCountListener(IOpenCountListener iOpenCountListener);

    void unregisterOpenCountListener(IOpenCountListener iOpenCountListener);

    void currentToWindow();

    void resetAllWindow();

    List<AppInfo> getAppList();

    void createWindowUserspace(in AppInfo appInfo);

    void getAppListAsync(IAppListCallback callback);

    void getAppIcon(IAppIconCallback callback, in AppInfo appInfo);

    void collapseStatusBarPanel();
}