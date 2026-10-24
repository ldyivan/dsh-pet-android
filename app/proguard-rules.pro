# dsh-pet Android 移植
# 源项目：https://github.com/PC2005-cloud/dsh-pet （MIT License）
# 本移植工程保留原项目 MIT 版权声明；动画/提示词等素材仅限开源使用，禁止商用。
-keep class com.dsh.pet.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}