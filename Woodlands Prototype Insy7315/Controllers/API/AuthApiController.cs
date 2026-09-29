using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using System.Text.Json;
using System.Text;
using Woodlands_Prototype_Insy7315.Models;
using Woodlands_Prototype_Insy7315.Services;

namespace Woodlands_Prototype_Insy7315.Controllers.Api
{
    /// <summary>
    /// Stateless JWT authentication for API consumers (the Android app, 
    /// future integrations). This works alongside the browser-based 
    /// AccountController (which uses cookie auth via Supabase). Both 
    /// authenticate against the same Supabase database, so a password 
    /// set in one works in the other.
    /// </summary>
    [ApiController]
    [Route("api/auth")]
    public class AuthApiController : ControllerBase
    {
        private readonly IHttpClientFactory _http;
        private readonly IJwtTokenService _jwtTokenService;
        private readonly ILogger<AuthApiController> _logger;
        private readonly JsonSerializerOptions _json = new() { PropertyNameCaseInsensitive = true };

        public AuthApiController(
            IHttpClientFactory http,
            IJwtTokenService jwtTokenService,
            ILogger<AuthApiController> logger)
        {
            _http = http;
            _jwtTokenService = jwtTokenService;
            _logger = logger;
        }

        public class LoginRequest
        {
            [Required, EmailAddress]
            public string Email { get; set; } = "";

            [Required]
            public string Password { get; set; } = "";
        }

        public class RegisterRequest
        {
            [Required, StringLength(100, MinimumLength = 2)]
            public string FullName { get; set; } = "";

            [Required, EmailAddress]
            public string Email { get; set; } = "";

            [Phone]
            public string? Phone { get; set; }

            [Required, MinLength(8)]
            public string Password { get; set; } = "";
        }

        public class TokenResponse
        {
            public string Token { get; set; } = "";
            public DateTime ExpiresAtUtc { get; set; }
            public string FullName { get; set; } = "";
            public string Email { get; set; } = "";
            public string Role { get; set; } = "";
            public string? Branch { get; set; }
        }

        /// <summary>
        /// Login with email and password. Returns JWT token if successful.
        /// Delegates password verification to Supabase API.
        /// </summary>
        [HttpPost("login")]
        [AllowAnonymous]
        public async Task<IActionResult> Login([FromBody] LoginRequest request)
        {
            if (!ModelState.IsValid)
                return ValidationProblem(ModelState);

            try
            {
                // Verify credentials via Supabase
                var client = _http.CreateClient("NodeApi");
                var loginPayload = new { email = request.Email, password = request.Password };
                var content = new StringContent(JsonSerializer.Serialize(loginPayload), Encoding.UTF8, "application/json");
                var res = await client.PostAsync("api/auth/login", content);

                if (!res.IsSuccessStatusCode)
                {
                    return Unauthorized(new { error = "Invalid email or password." });
                }

                // Get user details from Supabase to include in token
                var userRes = await client.GetAsync($"api/app-users?email={Uri.EscapeDataString(request.Email)}");
                if (!userRes.IsSuccessStatusCode)
                {
                    _logger.LogWarning("Could not retrieve user details after login for {Email}", request.Email);
                    return Unauthorized(new { error = "Invalid email or password." });
                }

                var userJson = await userRes.Content.ReadAsStringAsync();
                var users = JsonSerializer.Deserialize<List<AppUser>>(userJson, _json) ?? new();
                var user = users.FirstOrDefault();

                if (user == null)
                {
                    return Unauthorized(new { error = "Invalid email or password." });
                }

                var token = _jwtTokenService.CreateToken(user);
                var expiry = DateTime.UtcNow.AddMinutes(60);

                return Ok(new TokenResponse
                {
                    Token = token,
                    ExpiresAtUtc = expiry,
                    FullName = user.FullName,
                    Email = user.Email ?? "",
                    Role = user.Role ?? "Customer",
                    Branch = user.Branch
                });
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error during API login for {Email}", request.Email);
                return StatusCode(500, new { error = "An error occurred during login." });
            }
        }

        /// <summary>
        /// Register a new account. Returns JWT token immediately after registration.
        /// Delegates user creation to Supabase API.
        /// </summary>
        [HttpPost("register")]
        [AllowAnonymous]
        public async Task<IActionResult> Register([FromBody] RegisterRequest request)
        {
            if (!ModelState.IsValid)
                return ValidationProblem(ModelState);

            try
            {
                // Sanitize input before sending to API
                var sanitizedFullName = InputSanitizer.StripHtml(request.FullName) ?? "";

                // Create user via Supabase
                var client = _http.CreateClient("NodeApi");
                var registerPayload = new
                {
                    fullName = sanitizedFullName,
                    email = request.Email,
                    password = request.Password,
                    phone = request.Phone ?? ""
                };

                var content = new StringContent(JsonSerializer.Serialize(registerPayload), Encoding.UTF8, "application/json");
                var res = await client.PostAsync("api/auth/register", content);

                if (!res.IsSuccessStatusCode)
                {
                    var errJson = await res.Content.ReadAsStringAsync();
                    var error = GetUserFriendlyError(errJson);
                    return BadRequest(new { error });
                }

                // Retrieve the newly created user to include in token
                var userRes = await client.GetAsync($"api/app-users?email={Uri.EscapeDataString(request.Email)}");
                if (!userRes.IsSuccessStatusCode)
                {
                    _logger.LogWarning("Could not retrieve newly created user for {Email}", request.Email);
                    return BadRequest(new { error = "Registration failed." });
                }

                var userJson = await userRes.Content.ReadAsStringAsync();
                var users = JsonSerializer.Deserialize<List<AppUser>>(userJson, _json) ?? new();
                var user = users.FirstOrDefault();

                if (user == null)
                {
                    return BadRequest(new { error = "Registration failed." });
                }

                var token = _jwtTokenService.CreateToken(user);
                var expiry = DateTime.UtcNow.AddMinutes(60);

                return Ok(new TokenResponse
                {
                    Token = token,
                    ExpiresAtUtc = expiry,
                    FullName = user.FullName,
                    Email = user.Email ?? "",
                    Role = user.Role ?? "Customer",
                    Branch = user.Branch
                });
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error during API registration for {Email}", request.Email);
                return StatusCode(500, new { error = "An error occurred during registration." });
            }
        }

        /// <summary>
        /// Authenticated endpoint - verify bearer token is valid and return current user.
        /// Requires Authorization: Bearer &lt;token&gt; header.
        /// </summary>
        [HttpGet("me")]
        [Authorize(AuthenticationSchemes = "Bearer")]
        public IActionResult Me()
        {
            // Extract claims from bearer token
            var userId = User.FindFirst("sub")?.Value;
            var email = User.FindFirst("email")?.Value;
            var fullName = User.FindFirst("fullName")?.Value;
            var branch = User.FindFirst("branch")?.Value;
            var role = User.FindFirst("role")?.Value;

            if (string.IsNullOrWhiteSpace(userId))
                return Unauthorized();

            return Ok(new
            {
                id = userId,
                email,
                fullName,
                branch,
                role
            });
        }

        private static string GetUserFriendlyError(string errorJson)
        {
            try
            {
                var err = JsonSerializer.Deserialize<ErrorResponse>(errorJson,
                    new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
                var message = (err?.Error ?? "").ToLowerInvariant();

                if (message.Contains("already registered") || message.Contains("already exists"))
                    return "An account with this email address already exists.";
                if (message.Contains("password"))
                    return "The password does not meet the requirements.";
                if (message.Contains("email") && message.Contains("invalid"))
                    return "Please enter a valid email address.";
            }
            catch { }
            return "Registration failed.";
        }

        private class ErrorResponse { public string? Error { get; set; } }
    }
}
