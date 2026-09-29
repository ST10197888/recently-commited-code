// Woodlands Designer Boards — vanilla JS interactivity (ported from the React prototype's
// useState/useEffect logic into plain DOM manipulation, since Razor views are server-rendered).

document.addEventListener("DOMContentLoaded", function () {

    // ---------- Mobile nav toggle ----------
    var navToggle = document.getElementById("nav-toggle");
    var mobileMenu = document.getElementById("mobile-menu");
    if (navToggle && mobileMenu) {
        navToggle.addEventListener("click", function () {
            var opened = mobileMenu.classList.toggle("hidden") === false;
            navToggle.setAttribute("aria-expanded", opened ? "true" : "false");
        });
    }

    // ---------- Hero carousel ----------
    var slides = document.querySelectorAll("[data-hero-slide]");
    if (slides.length > 1) {
        var current = 0;
        var dots = document.querySelectorAll("[data-hero-dot]");
        var headingBlocks = document.querySelectorAll("[data-hero-copy]");

        function showSlide(idx) {
            slides.forEach(function (s, i) { s.classList.toggle("active", i === idx); });
            dots.forEach(function (d, i) {
                d.classList.toggle("w-6", i === idx);
                d.classList.toggle("bg-tan", i === idx);
                d.classList.toggle("w-2", i !== idx);
                d.classList.toggle("bg-white/40", i !== idx);
            });
            headingBlocks.forEach(function (h, i) {
                h.classList.toggle("hidden", i !== idx);
            });
            current = idx;
        }

        function next() { showSlide((current + 1) % slides.length); }
        function prev() { showSlide((current - 1 + slides.length) % slides.length); }

        var timer = setInterval(next, 5000); // note: prototype used 800ms — too fast to read, set to 5s here

        var nextBtn = document.querySelector("[data-hero-next]");
        var prevBtn = document.querySelector("[data-hero-prev]");
        if (nextBtn) nextBtn.addEventListener("click", function () { clearInterval(timer); next(); timer = setInterval(next, 5000); });
        if (prevBtn) prevBtn.addEventListener("click", function () { clearInterval(timer); prev(); timer = setInterval(next, 5000); });
        dots.forEach(function (d, i) {
            d.addEventListener("click", function () { clearInterval(timer); showSlide(i); timer = setInterval(next, 5000); });
        });
    }

    // ---------- Product detail gallery ----------
    var galleryMain = document.getElementById("gallery-main");
    var galleryThumbs = document.querySelectorAll("[data-gallery-thumb]");
    if (galleryMain && galleryThumbs.length) {
        galleryThumbs.forEach(function (thumb) {
            thumb.addEventListener("click", function () {
                galleryMain.src = thumb.getAttribute("data-src");
                galleryThumbs.forEach(function (t) { t.classList.remove("border-accent"); t.classList.add("border-transparent", "opacity-60"); });
                thumb.classList.add("border-accent");
                thumb.classList.remove("border-transparent", "opacity-60");
            });
        });
    }

    // ---------- FAQ accordion ----------
    document.querySelectorAll("[data-faq-toggle]").forEach(function (btn) {
        btn.addEventListener("click", function () {
            var body = btn.nextElementSibling;
            var chevron = btn.querySelector("[data-faq-chevron]");
            var isOpen = !body.classList.contains("max-h-0");
            document.querySelectorAll("[data-faq-body]").forEach(function (b) {
                b.classList.add("max-h-0");
                b.classList.remove("max-h-96");
            });
            document.querySelectorAll("[data-faq-chevron]").forEach(function (c) { c.classList.remove("rotate-180"); });
            if (!isOpen) {
                body.classList.remove("max-h-0");
                body.classList.add("max-h-96");
                chevron.classList.add("rotate-180");
            }
        });
    });

    // ---------- FAQ category filter ----------
    document.querySelectorAll("[data-faq-filter]").forEach(function (btn) {
        btn.addEventListener("click", function () {
            var cat = btn.getAttribute("data-faq-filter");
            document.querySelectorAll("[data-faq-filter]").forEach(function (b) {
                b.classList.remove("bg-brand-700", "text-white");
                b.classList.add("bg-white", "text-brand-700", "border", "border-brand-700/20");
            });
            btn.classList.add("bg-brand-700", "text-white");
            btn.classList.remove("bg-white", "text-brand-700", "border", "border-brand-700/20");

            document.querySelectorAll("[data-faq-item]").forEach(function (item) {
                var show = cat === "All" || item.getAttribute("data-faq-item") === cat;
                item.classList.toggle("hidden", !show);
            });
        });
    });

});
