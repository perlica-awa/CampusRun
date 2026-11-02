package com.happyrun.app.web

import com.happyrun.app.auth.platformUrls
import org.json.JSONObject

/**
 * 统一身份认证登录页的账密预填。
 *
 * 只填不提交：登录按钮仍然由用户按。这么做是因为 CAS 的账密登录经常还会要短信二次验证，
 * 预填能省掉的只是打字；替用户提交反而会把"某次验证码没收到"变成一次失败的账密尝试。
 *
 * 脚本在 document-start 时段注入（只对 CAS 站点生效），此时表单还没解析出来，
 * 所以自己轮询一段时间等字段出现。取值一律经 [JSONObject.quote] 转义，
 * 密码里带引号或反斜杠也不会把脚本打断。密码不进任何日志。
 */
object CasPrefill {

    val CAS_ORIGIN: String get() = platformUrls.casOrigin

    fun script(username: String, password: String): String {
        val user = JSONObject.quote(username)
        val pass = JSONObject.quote(password)
        return """
            (function(){
              if (window.__casPrefill) return;
              window.__casPrefill = 1;
              var user = $user, pass = $pass, tries = 0;
              function setValue(el, v) {
                try {
                  var d = Object.getOwnPropertyDescriptor(el.__proto__, 'value');
                  if (d && d.set) { d.set.call(el, v); } else { el.value = v; }
                } catch (e) { el.value = v; }
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
              }
              function tick() {
                tries++;
                var u = document.querySelector('#un') || document.querySelector('input[name="un"]');
                var p = document.querySelector('#pd') || document.querySelector('input[type="password"]');
                if (u && p) {
                  if (!u.value) setValue(u, user);
                  if (!p.value) setValue(p, pass);
                  return;
                }
                if (tries < 60) setTimeout(tick, 250);
              }
              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', tick);
              } else {
                tick();
              }
            })();
        """.trimIndent()
    }
}
