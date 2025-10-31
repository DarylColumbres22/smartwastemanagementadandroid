import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Calendar

class SendGridService(private val sendGridApiKey: String) {

    private val client = OkHttpClient()

    fun sendOtpEmail(
        toEmail: String,
        otp: String,
        appName: String = "SmartWasteManagementSystem",
        supportEmail: String = "darylcolumbres07@gmail.com",
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit
    ) {
        // API 25-safe current year
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        val html = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Your OTP Code</title>
          <style>
            body { margin:0; padding:0; background:#f6f8fb; }
            table { border-collapse:collapse; }
            a { text-decoration:none; }
          </style>
        </head>
        <body style="margin:0; padding:0; background:#f6f8fb;">
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f6f8fb;">
            <tr>
              <td align="center" style="padding:24px;">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:600px;background:#ffffff;border-radius:14px;overflow:hidden; box-shadow:0 4px 16px rgba(0,0,0,0.06);">
                  <!-- Header -->
                  <tr>
                    <td style="padding:24px 24px 0 24px; text-align:center;">
                      <div style="font:700 20px/1.2 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif; color:#111827;">
                        $appName
                      </div>
                      <div style="font:500 14px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif; color:#6b7280; margin-top:6px;">
                        Secure sign-in verification
                      </div>
                    </td>
                  </tr>

                  <!-- Divider -->
                  <tr>
                    <td style="padding:16px 24px 0;">
                      <hr style="border:none;border-top:1px solid #e5e7eb; margin:0;">
                    </td>
                  </tr>

                  <!-- Body -->
                  <tr>
                    <td style="padding:24px;">
                      <div style="font:400 16px/1.7 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif; color:#111827;">
                        Hi,
                        <br><br>
                        Use the One-Time Password (OTP) below to complete your login. For your security, this code expires in <strong>5 minutes</strong>.
                      </div>

                      <!-- OTP box -->
                      <div style="margin:20px 0; text-align:center;">
                        <div style="
                          display:inline-block;
                          font:700 28px/1.2 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
                          letter-spacing:4px;
                          color:#111827;
                          background:#f3f4f6;
                          border:1px solid #e5e7eb;
                          border-radius:12px;
                          padding:16px 24px;
                        ">
                          $otp
                        </div>
                      </div>

                      <div style="font:400 13px/1.7 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif; color:#6b7280; margin-top:16px;">
                        Didn’t request this code? You can safely ignore this email, but we recommend updating your account password.
                      </div>
                    </td>
                  </tr>

                  <!-- Footer -->
                  <tr>
                    <td style="background:#f9fafb; padding:16px 24px; text-align:center;">
                      <div style="font:400 12px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif; color:#6b7280;">
                        Need help? Contact us at <a href="mailto:$supportEmail" style="color:#111827;">$supportEmail</a>
                      </div>
                    </td>
                  </tr>

                </table>
                <div style="font:400 11px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif; color:#9ca3af; margin-top:12px;">
                  © $currentYear $appName. All rights reserved.
                </div>
              </td>
            </tr>
          </table>
        </body>
        </html>
        """.trimIndent()

        val plainText = """
        $appName — Secure sign-in
        
        Your one-time password (OTP) is: $otp
        This code expires in 5 minutes.

        If you didn’t request this code, you can ignore this email.
        Need help? Contact $supportEmail
        """.trimIndent()

        val json = """
            {
              "personalizations": [
                {
                  "to": [ { "email": "$toEmail" } ],
                  "subject": "Your OTP Login Code"
                }
              ],
              "from": { "email": "darylcolumbres07@gmail.com", "name": "$appName" },
              "reply_to": { "email": "darylcolumbres07@gmail.com", "name": "$appName Support" },
            
              "categories": ["transactional", "otp"],
            
              "mail_settings": {
                "bypass_list_management": { "enable": true }
              },
              "tracking_settings": {
                "click_tracking": { "enable": false, "enable_text": false },
                "open_tracking":  { "enable": false },
                "subscription_tracking": { "enable": false }
              },
            
              "content": [
                { "type": "text/plain", "value": ${plainText.toJsonString()} },
                { "type": "text/html",  "value": ${html.toJsonString()} }
              ]
            }
            """.trimIndent()


        val mediaType = "application/json".toMediaType()
        val body = json.toRequestBody(mediaType)

        val request = Request.Builder()
            .url("https://api.sendgrid.com/v3/mail/send")
            .post(body)
            .addHeader("Authorization", "Bearer $sendGridApiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFailure(e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (r.isSuccessful) {
                        onSuccess()
                    } else {
                        val errorBody = r.body?.string()
                        onFailure((
                                "HTTP ${r.code}: ${r.message}" +
                                        (if (!errorBody.isNullOrBlank()) " — $errorBody" else "")
                                ).trim())
                    }
                }
            }
        })
    }


    fun sendLoginApprovalEmail(
        toEmail: String,
        appName: String = "SmartWasteManagementSystem",
        supportEmail: String = "darylcolumbres07@gmail.com",
        deviceName: String,
        deviceId: String,
        locationLabel: String?,
        approvedAtMillis: Long,
        approvedBy: String?,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit
    ) {
        val approvedAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply {
            timeZone = java.util.TimeZone.getDefault()
        }.format(java.util.Date(approvedAtMillis))

        val safeLocation = locationLabel?.takeIf { it.isNotBlank() } ?: "Unknown location"

        val html = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Login Approved</title>
          <style>body{margin:0;padding:0;background:#f6f8fb}table{border-collapse:collapse}</style>
        </head>
        <body style="margin:0;padding:0;background:#f6f8fb;">
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f6f8fb;">
            <tr>
              <td align="center" style="padding:24px;">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:600px;background:#ffffff;border-radius:14px;overflow:hidden;box-shadow:0 4px 16px rgba(0,0,0,0.06);">
                  <tr>
                    <td style="padding:24px 24px 0;text-align:center;">
                      <div style="font:700 20px/1.2 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#111827;">$appName</div>
                      <div style="font:500 14px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#6b7280;margin-top:6px;">
                        Login approval confirmation
                      </div>
                    </td>
                  </tr>
                  <tr><td style="padding:16px 24px 0;"><hr style="border:none;border-top:1px solid #e5e7eb;margin:0;"></td></tr>
                  <tr>
                    <td style="padding:24px;">
                      <div style="font:400 16px/1.7 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#111827;">
                        Your login request was <strong>approved</strong>.
                      </div>
                      <div style="margin-top:14px;font:400 14px/1.7 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#111827;">
                        <table role="presentation" cellpadding="0" cellspacing="0" width="100%" style="border:1px solid #e5e7eb;border-radius:12px;">
                          <tr><td style="padding:12px 16px;background:#f9fafb;width:40%;font-weight:600;">Device</td><td style="padding:12px 16px;">$deviceName</td></tr>
                          <tr><td style="padding:12px 16px;background:#f9fafb;font-weight:600;">Device ID</td><td style="padding:12px 16px;">$deviceId</td></tr>
                          <tr><td style="padding:12px 16px;background:#f9fafb;font-weight:600;">Location</td><td style="padding:12px 16px;">$safeLocation</td></tr>
                          <tr><td style="padding:12px 16px;background:#f9fafb;font-weight:600;">Approved at</td><td style="padding:12px 16px;">$approvedAt</td></tr>
                          ${if (!approvedBy.isNullOrBlank()) "<tr><td style=\"padding:12px 16px;background:#f9fafb;font-weight:600;\">Approved by</td><td style=\"padding:12px 16px;\">$approvedBy</td></tr>" else ""}
                        </table>
                      </div>
                      <div style="font:400 13px/1.7 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#6b7280;margin-top:16px;">
                        If this wasn’t you, secure your account immediately by resetting your password and reviewing your sessions.
                      </div>
                    </td>
                  </tr>
                  <tr>
                    <td style="background:#f9fafb;padding:16px 24px;text-align:center;">
                      <div style="font:400 12px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#6b7280;">
                        Need help? Contact <a href="mailto:$supportEmail" style="color:#111827;">$supportEmail</a>
                      </div>
                    </td>
                  </tr>
                </table>
              </td>
            </tr>
          </table>
        </body>
        </html>
        """.trimIndent()

        val plainText = """
        $appName — Login Approved

        Device: $deviceName
        Device ID: $deviceId
        Location: ${safeLocation}
        Approved at: $approvedAt
        ${if (!approvedBy.isNullOrBlank()) "Approved by: $approvedBy" else ""}

        If this wasn’t you, reset your password and review your sessions.
        """.trimIndent()

        val json = """
        {
          "personalizations": [
            {
              "to": [ { "email": "$toEmail" } ],
              "subject": "Login approved for your account"
            }
          ],
          "from": { "email": "darylcolumbres07@gmail.com", "name": "$appName" },
          "reply_to": { "email": "darylcolumbres07@gmail.com", "name": "$appName Support" },
        
          "categories": ["transactional", "login_approval"],
        
          "mail_settings": {
            "bypass_list_management": { "enable": true }
          },
          "tracking_settings": {
            "click_tracking": { "enable": false, "enable_text": false },
            "open_tracking":  { "enable": false },
            "subscription_tracking": { "enable": false }
          },
        
          "content": [
            { "type": "text/plain", "value": ${plainText.toJsonString()} },
            { "type": "text/html",  "value": ${html.toJsonString()} }
          ]
        }
        """.trimIndent()


        val mediaType = "application/json".toMediaType()
        val body = json.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://api.sendgrid.com/v3/mail/send")
            .post(body)
            .addHeader("Authorization", "Bearer $sendGridApiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onFailure(e.message)
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (r.isSuccessful) onSuccess()
                    else onFailure("HTTP ${r.code}: ${r.message} ${r.body?.string() ?: ""}".trim())
                }
            }
        })
    }
}

private fun String.toJsonString(): String {
    val sb = StringBuilder("\"")
    for (ch in this) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '\"' -> sb.append("\\\"")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
        }
    }
    sb.append("\"")
    return sb.toString()
}
