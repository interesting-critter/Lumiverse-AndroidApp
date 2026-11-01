use tauri::plugin::{Builder, TauriPlugin};

#[cfg(target_os = "android")]
struct RuntimeHandle(tauri::plugin::PluginHandle<tauri::Wry>);

// Only the bundled connection page has permission to call this command.
#[tauri::command]
async fn mobile_connection(
    app: tauri::AppHandle,
    action: String,
    server: Option<String>,
    port: Option<u32>,
) -> Result<serde_json::Value, String> {
    #[cfg(target_os = "android")]
    {
        use tauri::Manager;
        app.state::<RuntimeHandle>().0.run_mobile_plugin(
            "connection", serde_json::json!({ "action": action, "server": server, "port": port }),
        ).map_err(|error| error.to_string())
    }
    #[cfg(not(target_os = "android"))]
    { let _ = (app, action, server, port); Err("Android connection controls are unavailable".into()) }
}

#[tauri::command]
fn mobile_host_info() -> serde_json::Value {
    serde_json::json!({ "host": "tauri", "version": tauri::VERSION, "platform": "android" })
}

fn runtime_plugin() -> TauriPlugin<tauri::Wry> {
    Builder::new("lumiverse-runtime")
        .js_init_script(format!("{}\n{}\n{}\n{}", "document.documentElement?.setAttribute('data-tauri-mobile', 'android'); document.documentElement?.setAttribute('data-platform', 'android'); Object.defineProperty(window, '__LUMIVERSE_MOBILE__', {value: true}); document.addEventListener('DOMContentLoaded', () => { document.documentElement.setAttribute('data-tauri-mobile', 'android'); document.documentElement.setAttribute('data-platform', 'android'); });", include_str!("downloads.js"), include_str!("connection-ready.js"), include_str!("device-motion.js")))
        .setup(|_app, api| {
            #[cfg(target_os = "android")]
            {
                use tauri::Manager;
                let handle = api.register_android_plugin("chat.lumiverse.prototype", "RuntimePlugin")?;
                _app.manage(RuntimeHandle(handle));
            }
            Ok(())
        })
        .on_navigation(|_webview, url| {
            matches!(url.scheme(), "tauri" | "asset") ||
                // Hosted login flows can redirect through an identity provider.
                // Navigation does not grant native IPC permissions to websites.
                matches!(url.scheme(), "http" | "https")
        })
        .build()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(runtime_plugin())
        .invoke_handler(tauri::generate_handler![mobile_host_info, mobile_connection])
        .run(tauri::generate_context!())
        .expect("Failed to run Lumiverse Android");
}
