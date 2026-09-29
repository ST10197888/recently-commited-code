using Microsoft.AspNetCore.Mvc;
using System.Collections.Generic;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;
using Woodlands_Prototype_Insy7315.Models;

namespace Woodlands_Prototype_Insy7315.Controllers
{
    public class BranchesController : Controller
    {
        private readonly IHttpClientFactory _httpClientFactory;

        public BranchesController(IHttpClientFactory httpClientFactory)
        {
            _httpClientFactory = httpClientFactory;
        }

        public async Task<IActionResult> Index()
        {
            // Create the client using the configuration from Program.cs
            var client = _httpClientFactory.CreateClient("NodeApi");

            // Call the API endpoint
            var response = await client.GetAsync("api/branches");

            if (response.IsSuccessStatusCode)
            {

                var jsonString = await response.Content.ReadAsStringAsync();

                // Convert the JSON into a list of C# Branch objects
                var options = new JsonSerializerOptions { PropertyNameCaseInsensitive = true };
                var branches = JsonSerializer.Deserialize<List<Branch>>(jsonString, options);

                return View(branches);
            }

            return View(new List<Branch>());
        }
    }
}