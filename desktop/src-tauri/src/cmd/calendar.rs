//! The calendars already on this Mac (Calendar.app's accounts: iCloud, Google, Exchange...), read
//! through EventKit. Nothing is fetched from a server: macOS has synced them already. Which event
//! a recording belongs to is the frontend's call (`desktop/src/lib/calendar.ts`); this only lists.

use super::permissions::PermissionStatus;
use serde::Serialize;

/// One timed event overlapping the asked range. Mirrors `CalendarEvent` in src/lib/calendar.ts.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CalendarEvent {
    pub id: String,
    pub title: String,
    /// Milliseconds since the Unix epoch.
    pub start_ms: f64,
    pub end_ms: f64,
    pub calendar: Option<String>,
    /// Display names, falling back to the address; calendar order.
    pub attendees: Vec<String>,
}

#[cfg(target_os = "macos")]
fn status() -> PermissionStatus {
    use objc2_event_kit::{EKAuthorizationStatus, EKEntityType, EKEventStore};
    match unsafe { EKEventStore::authorizationStatusForEntityType(EKEntityType::Event) } {
        EKAuthorizationStatus::FullAccess => PermissionStatus::Granted,
        EKAuthorizationStatus::NotDetermined => PermissionStatus::NotDetermined,
        EKAuthorizationStatus::Restricted => PermissionStatus::Restricted,
        // Write-only access cannot read events, so for naming recordings it is a refusal.
        _ => PermissionStatus::Denied,
    }
}

#[tauri::command]
pub fn get_calendar_permission_status() -> PermissionStatus {
    #[cfg(target_os = "macos")]
    {
        status()
    }
    #[cfg(not(target_os = "macos"))]
    {
        PermissionStatus::NotApplicable
    }
}

#[tauri::command]
pub async fn request_calendar_permission() -> PermissionStatus {
    #[cfg(target_os = "macos")]
    {
        use block2::RcBlock;
        use objc2::runtime::Bool;
        use objc2_event_kit::EKEventStore;
        use objc2_foundation::NSError;

        let current = status();
        if current != PermissionStatus::NotDetermined {
            return current;
        }
        let (sender, receiver) = tokio::sync::oneshot::channel();
        {
            let sender = std::sync::Mutex::new(Some(sender));
            let completion = RcBlock::new(move |granted: Bool, _error: *mut NSError| {
                if let Some(sender) = sender.lock().ok().and_then(|mut sender| sender.take()) {
                    let _ = sender.send(granted.as_bool());
                }
            });
            let store = unsafe { EKEventStore::new() };
            unsafe { store.requestFullAccessToEventsWithCompletion(RcBlock::as_ptr(&completion)) };
        }
        match receiver.await {
            Ok(true) => PermissionStatus::Granted,
            Ok(false) => PermissionStatus::Denied,
            Err(_) => status(),
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        PermissionStatus::NotApplicable
    }
}

/// Timed events overlapping `[start_ms, end_ms]`, from every calendar. Empty without access.
#[tauri::command]
pub fn calendar_events(start_ms: f64, end_ms: f64) -> Vec<CalendarEvent> {
    #[cfg(target_os = "macos")]
    {
        if status() != PermissionStatus::Granted || end_ms <= start_ms {
            return Vec::new();
        }
        events_between(start_ms, end_ms)
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = (start_ms, end_ms);
        Vec::new()
    }
}

#[cfg(target_os = "macos")]
fn events_between(start_ms: f64, end_ms: f64) -> Vec<CalendarEvent> {
    use objc2_event_kit::EKEventStore;
    use objc2_foundation::NSDate;

    let store = unsafe { EKEventStore::new() };
    let start = NSDate::dateWithTimeIntervalSince1970(start_ms / 1000.0);
    let end = NSDate::dateWithTimeIntervalSince1970(end_ms / 1000.0);
    let predicate = unsafe { store.predicateForEventsWithStartDate_endDate_calendars(&start, &end, None) };
    let events = unsafe { store.eventsMatchingPredicate(&predicate) };
    events
        .iter()
        .filter(|event| unsafe { !event.isAllDay() })
        .map(|event| unsafe {
            let attendees = event
                .attendees()
                .map(|people| {
                    people
                        .iter()
                        .filter_map(|person| {
                            let name = person.name().map(|name| name.to_string()).unwrap_or_default();
                            let name = name.trim();
                            if !name.is_empty() {
                                return Some(name.to_string());
                            }
                            // `mailto:someone@example.com` when the invite carries no name.
                            let url = person.URL().absoluteString().map(|url| url.to_string()).unwrap_or_default();
                            let address = url.strip_prefix("mailto:").unwrap_or(&url).trim().to_string();
                            (!address.is_empty()).then_some(address)
                        })
                        .collect()
                })
                .unwrap_or_default();
            CalendarEvent {
                id: event.eventIdentifier().map(|id| id.to_string()).unwrap_or_default(),
                title: event.title().to_string(),
                start_ms: event.startDate().timeIntervalSince1970() * 1000.0,
                end_ms: event.endDate().timeIntervalSince1970() * 1000.0,
                calendar: event.calendar().map(|calendar| calendar.title().to_string()),
                attendees,
            }
        })
        .collect()
}

#[tauri::command]
pub fn open_calendar_settings() {
    #[cfg(target_os = "macos")]
    {
        let url = "x-apple.systempreferences:com.apple.preference.security?Privacy_Calendars";
        if let Err(error) = std::process::Command::new("open").arg(url).spawn() {
            tracing::warn!("could not open calendar privacy settings: {error}");
        }
    }
}
