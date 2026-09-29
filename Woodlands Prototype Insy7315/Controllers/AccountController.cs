using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Mvc;
using Woodlands_Prototype_Insy7315.Models;
using Woodlands_Prototype_Insy7315.Services;

namespace Woodlands_Prototype_Insy7315.Controllers
{
    public class AccountController : Controller
    {
        private readonly SupabaseAuthService _authService;
        private readonly ILogger<AccountController> _logger;

        public AccountController(
            SupabaseAuthService authService,
            ILogger<AccountController> logger)
        {
            _authService = authService;
            _logger = logger;
        }

        [HttpGet]
        public IActionResult Login(string? returnUrl = null)
        {
            ViewData["ReturnUrl"] = returnUrl;

            return View(new LoginViewModel
            {
                ReturnUrl = returnUrl
            });
        }

        [HttpPost]
        [ValidateAntiForgeryToken]
        public async Task<IActionResult> Login(
            LoginViewModel model,
            string? returnUrl = null)
        {
            if (!ModelState.IsValid)
            {
                return View(model);
            }

            var result = await _authService.LoginAsync(
                model.Email,
                model.Password);

            if (!result.Success || result.User == null)
            {
                ModelState.AddModelError(
                    "",
                    result.Error ?? "Invalid email or password.");

                return View(model);
            }

            var claims = CreateClaims(result.User);

            var identity = new ClaimsIdentity(
                claims,
                "WoodlandsCookie");

            var principal = new ClaimsPrincipal(identity);

            var authenticationProperties =
                new AuthenticationProperties
                {
                    IsPersistent = model.RememberMe,
                    ExpiresUtc = model.RememberMe
                        ? DateTimeOffset.UtcNow.AddDays(30)
                        : DateTimeOffset.UtcNow.AddHours(8)
                };

            await HttpContext.SignInAsync(
                "WoodlandsCookie",
                principal,
                authenticationProperties);

            if (!string.IsNullOrWhiteSpace(returnUrl) &&
                Url.IsLocalUrl(returnUrl))
            {
                return Redirect(returnUrl);
            }

            return RedirectToAction(
                "Index",
                "Dashboard");
        }

        [HttpGet]
        public IActionResult Register()
        {
            return View(new RegisterViewModel());
        }

        [HttpPost]
        [ValidateAntiForgeryToken]
        public async Task<IActionResult> Register(
            RegisterViewModel model)
        {
            if (!ModelState.IsValid)
            {
                return View(model);
            }

            var result = await _authService.RegisterAsync(
                model.FullName,
                model.Email,
                model.Password,
                model.PhoneNumber);

            if (!result.Success)
            {
                ModelState.AddModelError(
                    "",
                    result.Error ?? "Registration failed.");

                return View(model);
            }

            TempData["RegistrationMessage"] =
                "Your account has been created. You can now sign in.";

            return RedirectToAction(nameof(Login));
        }

        [HttpPost]
        [ValidateAntiForgeryToken]
        public async Task<IActionResult> Logout()
        {
            await HttpContext.SignOutAsync("WoodlandsCookie");

            return RedirectToAction(
                "Index",
                "Home");
        }

        [HttpGet]
        public IActionResult AccessDenied()
        {
            return View();
        }

        private static List<Claim> CreateClaims(
            AppUser user)
        {
            return new List<Claim>
            {
                new Claim(
                    ClaimTypes.NameIdentifier,
                    user.Id),

                new Claim(
                    ClaimTypes.Name,
                    user.Email),

                new Claim(
                    ClaimTypes.Email,
                    user.Email),

                new Claim(
                    "FullName",
                    user.FullName),

                new Claim(
                    ClaimTypes.Role,
                    user.Role),

                new Claim(
                    "Branch",
                    user.Branch ?? "")
            };
        }
    }
}