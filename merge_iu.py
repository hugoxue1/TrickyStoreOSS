# -*- coding: utf-8 -*-
import io

# 合并策略：底板=我们（APatch 双模式注入 + 永不退出重试，129 号档修复），
# 嫁接上游两处改进：① setupInterceptor 注册失败即中止（新 binder lib 返回 Boolean）
#                    ② extractAlias 用 Credentials.* 前缀精确匹配（修复含下划线别名）

ours = io.open('/tmp/iu_ours.kt', encoding='utf-8').read()
ups = io.open('/tmp/iu_upstream.kt', encoding='utf-8').read()

# ① setupInterceptor 替换
old_setup = '''    protected open fun setupInterceptor(service: IBinder, backdoor: IBinder): Boolean {
        Log.i(TAG, "Registering for $serviceName: $keystore")
        registerBinderInterceptor(backdoor, service, this)
        keystore = service
'''
new_setup = '''    protected open fun setupInterceptor(service: IBinder, backdoor: IBinder): Boolean {
        Logger.i("Registering for $serviceName: $service")

        // [v3.1.1-ckb1 升级采纳] 新 binder lib 的 registerBinderInterceptor 返回 Boolean，
        // 注册失败时中止 setup（避免半初始化状态）
        if (!registerBinderInterceptor(backdoor, service, this)) {
            Logger.e("Failed to register interceptor for $serviceName; aborting setup")
            return false
        }

        keystore = service
'''
assert old_setup in ours, 'setupInterceptor pattern missing in ours'
merged = ours.replace(old_setup, new_setup, 1)

# ② extractAlias 替换（保留在同位置）
m = ups.find('    fun String.extractAlias(): String =')
assert m >= 0, 'upstream extractAlias not found'
end = ups.find('\n    }\n', m)
assert end > m
up_extract = ups[m:end + len('\n    }')]

m2 = merged.find('    fun String.extractAlias(): String {')
assert m2 >= 0, 'ours extractAlias not found'
end2 = merged.find('\n    }\n', m2)
assert end2 > m2
merged = merged[:m2] + up_extract + merged[end2 + len('\n    }'):]

# ③ Logger import（上游 Logger 对象风格在我们嫁接的方法里使用）
if 'import io.github.beakthoven.TrickyStoreOSS.logging.Logger' not in merged:
    merged = merged.replace(
        'import io.github.beakthoven.TrickyStoreOSS.logging.TAG\n',
        'import io.github.beakthoven.TrickyStoreOSS.logging.Logger\n', 1)

io.open('app/src/main/java/io/github/beakthoven/TrickyStoreOSS/interceptors/InterceptorUtils.kt',
        'w', encoding='utf-8', newline='').write(merged)
print('InterceptorUtils merged')
