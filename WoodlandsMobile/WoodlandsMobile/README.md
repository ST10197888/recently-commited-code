# Woodlands Designer Boards – Mobile Prototype

This is a native Android Studio project created from the current Woodlands Designer Boards ASP.NET Core MVC prototype and the mobile wireframes in `TASK 1 (1).docx`.

## Current architecture

- **Android app:** Kotlin, native Android Views, local SQLite database.
- **No API:** the mobile app deliberately does not call the ASP.NET website or its database.
- **Independent data:** the app seeds its own local SQLite database on first launch.
- **Website:** the original ASP.NET Core MVC website remains unchanged and continues using its existing local SQLite database.
- **Future-ready:** the repository is structured so the local database layer can later be replaced by an API/repository layer without redesigning the screens.

## Mobile functions included

- Home/dashboard style landing screen.
- Product/service gallery with category filtering.
- Product detail pages with features, finishes, pricing and lead time.
- Product-to-quote flow, with a quote request form and confirmation.
- Branch locator with Maps intent.
- About Us, Testimonials, FAQs (category filters, expandable answers), Contact form.
- Persistent bottom navigation: Home, Gallery, Quote, Branches, More.
- A genuine slide-in sidebar (opened from the header's ☰ button) with site navigation and
  account actions, separate from the More tab — matching the website's mobile hamburger menu.
- Every tappable element (buttons, cards, chips, nav items) has a ripple/darken touch reaction,
  and the active bottom-nav tab is highlighted.
- **Accounts, roles and permissions**, mirroring the website's ASP.NET Core Identity setup:
  - Register / Login / Logout, with the same seeded prototype accounts as the website
    (`admin@woodlandsdb.co.za` / `admin123`, `soweto@woodlandsdb.co.za` / `manager123`,
    `roodepoort@woodlandsdb.co.za` / `manager123`, `randfontein@woodlandsdb.co.za` / `manager123`,
    `customer@example.com` / `customer123`). "Quick login" buttons for these live in the More
    tab, below Login/Register, and are hidden once signed in — just like the website hides
    Login/Register once authenticated.
  - Profile screen (view role/branch, edit name/phone, change password) and a Settings screen
    that mirrors the website's Dashboard ▸ Settings "My Account"/"Security" panels.
  - Role-aware Dashboard: Admin sees totals, per-branch summaries and links to manage Users,
    Products, Testimonials and FAQs; Branch Managers see their branch's pending/in-progress/
    completed counts; Customers don't get a dashboard, just My Quotes.
  - Quotes screen: Admins see every quote, Managers see their branch's quotes (with status
    controls: Pending/In Progress/Completed/Cancelled), Customers see only their own quotes
    (read-only) — the same visibility rules as `DashboardController` on the website.
  - Full CRUD management screens for Products (Admin + Managers) and, Admin-only, Users,
    Testimonials and FAQs — the same permission split as `ManagementController`/`AdminController`.
- Local seeded data matching the website's current catalogue, services, FAQs, testimonials,
  prototype user accounts and a handful of demo quotes for the dashboards.

## Open in Android Studio

1. Open the `WoodlandsMobile` folder in Android Studio.
2. Allow Android Studio to sync Gradle and install any requested Android SDK components.
3. Use an Android emulator or a physical Android device.
4. Run the `app` configuration.

The project intentionally does not include generated `build/`, `.gradle/`, or IDE-specific files.

## Important prototype behaviour

The current project has no shared server/API layer by design. A quote or contact submission is written only to the Android device's local SQLite database. It is not sent to the website.

The source website contains several Unsplash image URLs. To keep this Android prototype usable without requiring a network image service, the app uses the supplied local Woodlands product photographs as packaged fallback imagery for those catalogue entries.

## Where to edit the design

The code is split by concern so no single file gets unwieldy:

- `MainActivity.kt` — app chrome (header, bottom nav), the public content screens (home,
  gallery, product, quote, branches, more, about, testimonials, faqs, contact), and every
  shared UI helper (`button`, `card`, `chip`, `field`, colours, ripple/touch-reaction backgrounds).
- `Sidebar.kt` — the slide-in navigation drawer opened from the header's ☰ button.
- `AuthScreens.kt` — Login, Register, Profile, Settings.
- `AdminScreens.kt` — Dashboard, Quotes, and the Users/Products/Testimonials/FAQs management
  screens, gated by role.
- `LocalDb.kt` — SQLite schema and seed data (catalogue + prototype user accounts + demo quotes).
- `Queries.kt` — all read/write helpers against `LocalDb`, used by every screen file.
- `Models.kt` — data classes plus the `Roles` object mirroring `IdentitySeederRoles` on the website.
- `Security.kt` / `Session.kt` — local password hashing and the signed-in-user session.

Colours, typography and layout constants are intentionally straightforward so the design can be changed quickly.

The packaged product images are in:

`app/src/main/res/drawable-nodpi/`

## Later migration to an API

When the custom database/third-party database is selected, replace the local `LocalDb` access with a repository/API implementation. The intended future path is:

`Android UI -> Repository -> REST API -> ASP.NET Core services -> EF Core -> production database`

Do not make the Android app connect directly to the production SQL database.
