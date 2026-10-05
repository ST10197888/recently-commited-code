using Woodlands_Prototype_Insy7315.Services;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddControllersWithViews();
builder.Services.AddRazorPages();

// Register HttpClient to use Railway first and the local API as fallback.
builder.Services.AddTransient<HostedFirstFallbackHandler>();

builder.Services
    .AddHttpClient("NodeApi", client =>
    {
        var hostedUrl =
            builder.Configuration["NodeApi:HostedBaseUrl"]
            ?? "https://insy7315-api-repository-production.up.railway.app/";

        client.BaseAddress = new Uri(hostedUrl);
        client.Timeout = TimeSpan.FromSeconds(8);
    })
    .AddHttpMessageHandler<HostedFirstFallbackHandler>();

builder.Services.AddScoped<SupabaseAuthService>();

// CORS for the Android app
builder.Services.AddCors(options =>
{
    options.AddPolicy("AllowAndroidApp", policy =>
        policy.AllowAnyOrigin().AllowAnyMethod().AllowAnyHeader());
});

// Cookie authentication
builder.Services
    .AddAuthentication(options =>
    {
        options.DefaultAuthenticateScheme = "WoodlandsCookie";
        options.DefaultChallengeScheme = "WoodlandsCookie";
        options.DefaultSignInScheme = "WoodlandsCookie";
    })
    .AddCookie("WoodlandsCookie", options =>
    {
        options.LoginPath = "/Account/Login";
        options.AccessDeniedPath = "/Account/AccessDenied";
        options.ExpireTimeSpan = TimeSpan.FromHours(8);
        options.SlidingExpiration = true;
    });

builder.Services.AddAuthorization();

var app = builder.Build();

if (app.Environment.IsDevelopment())
{
    app.UseDeveloperExceptionPage();
}
else
{
    app.UseExceptionHandler("/Home/Error");
    app.UseHsts();
}

app.UseHttpsRedirection();
app.UseStaticFiles();
app.UseRouting();
app.UseCors("AllowAndroidApp");
app.UseAuthentication();
app.UseAuthorization();

app.MapControllerRoute(
    name: "default",
    pattern: "{controller=Home}/{action=Index}/{id?}");
app.MapRazorPages();

app.Run();