package com.woodlands.mobile

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ProductRepository(private val localDb: LocalDb) {
    private val gson = Gson()

    suspend fun syncProducts(): Result<List<Product>> = try {
        val response = SupabaseClient.get("/rest/v1/products?select=*&order=category.asc,title.asc")

        when (response) {
            is ApiResponse.Success -> {
                val products = gson.fromJson<List<Product>>(
                    response.data,
                    object : TypeToken<List<Product>>() {}.type
                )

                if (products != null) {
                    localDb.updateProducts(products)
                    Result.success(products)
                } else {
                    Result.failure(Exception("Failed to parse products"))
                }
            }
            is ApiResponse.Error -> {
                Result.failure(Exception("API Error: ${response.code} - ${response.message}"))
            }
            is ApiResponse.Exception -> {
                Result.failure(response.exception)
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun syncProduct(productId: String): Result<Product?> = try {
        val response = SupabaseClient.get("/rest/v1/products?id=eq.$productId&select=*")

        when (response) {
            is ApiResponse.Success -> {
                val products = gson.fromJson<List<Product>>(
                    response.data,
                    object : TypeToken<List<Product>>() {}.type
                )
                Result.success(products?.firstOrNull())
            }
            is ApiResponse.Error -> {
                Result.failure(Exception("API Error: ${response.code}"))
            }
            is ApiResponse.Exception -> {
                Result.failure(response.exception)
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun getProducts(): List<Product> = localDb.loadProducts()
}

class TestimonialRepository(private val localDb: LocalDb) {
    private val gson = Gson()

    suspend fun syncTestimonials(): Result<List<Testimonial>> = try {
        val response = SupabaseClient.get("/rest/v1/testimonials?select=*&order=id.asc")

        when (response) {
            is ApiResponse.Success -> {
                val testimonials = gson.fromJson<List<Testimonial>>(
                    response.data,
                    object : TypeToken<List<Testimonial>>() {}.type
                )

                if (testimonials != null) {
                    localDb.updateTestimonials(testimonials)
                    Result.success(testimonials)
                } else {
                    Result.failure(Exception("Failed to parse testimonials"))
                }
            }
            is ApiResponse.Error -> {
                Result.failure(Exception("API Error: ${response.code}"))
            }
            is ApiResponse.Exception -> {
                Result.failure(response.exception)
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun getTestimonials(): List<Testimonial> = localDb.loadTestimonials()
}

class FaqRepository(private val localDb: LocalDb) {
    private val gson = Gson()

    suspend fun syncFaqs(): Result<List<Faq>> = try {
        val response = SupabaseClient.get("/rest/v1/faqs?select=*&order=category.asc")

        when (response) {
            is ApiResponse.Success -> {
                val faqs = gson.fromJson<List<Faq>>(
                    response.data,
                    object : TypeToken<List<Faq>>() {}.type
                )

                if (faqs != null) {
                    localDb.updateFaqs(faqs)
                    Result.success(faqs)
                } else {
                    Result.failure(Exception("Failed to parse FAQs"))
                }
            }
            is ApiResponse.Error -> {
                Result.failure(Exception("API Error: ${response.code}"))
            }
            is ApiResponse.Exception -> {
                Result.failure(response.exception)
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun getFaqs(): List<Faq> = localDb.loadFaqs()

    fun getFaqsByCategory(category: String): List<Faq> = localDb.loadFaqsByCategory(category)
}

class BranchRepository(private val localDb: LocalDb) {
    private val gson = Gson()

    suspend fun syncBranches(): Result<List<Branch>> = try {
        val response = SupabaseClient.get("/rest/v1/branches?select=*&order=id.asc")

        when (response) {
            is ApiResponse.Success -> {
                val branches = gson.fromJson<List<Branch>>(
                    response.data,
                    object : TypeToken<List<Branch>>() {}.type
                )

                if (branches != null) {
                    localDb.updateBranches(branches)
                    Result.success(branches)
                } else {
                    Result.failure(Exception("Failed to parse branches"))
                }
            }
            is ApiResponse.Error -> {
                Result.failure(Exception("API Error: ${response.code}"))
            }
            is ApiResponse.Exception -> {
                Result.failure(response.exception)
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun getBranches(): List<Branch> = localDb.loadBranches()
}

class QuoteRepository {
    private val gson = Gson()

    data class CreateQuoteRequest(
        val firstName: String,
        val lastName: String,
        val email: String,
        val phone: String,
        val branch: String,
        val service: String,
        val message: String,
        val productId: String?
    )

    data class QuoteResponse(
        val quoteCode: String,
        val message: String
    )

    suspend fun submitQuote(quote: CreateQuoteRequest): Result<String> = try {
        val response = SupabaseClient.post(
            "/rest/v1/rpc/create_quote_request",
            quote
        )

        when (response) {
            is ApiResponse.Success -> {
                val quoteResponse = gson.fromJson(response.data, QuoteResponse::class.java)
                Result.success(quoteResponse.quoteCode)
            }
            is ApiResponse.Error -> {
                Result.failure(Exception("Failed to submit quote: ${response.message}"))
            }
            is ApiResponse.Exception -> {
                Result.failure(response.exception)
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

class AuthRepository(private val tokenManager: AuthTokenManager) {
    private val gson = Gson()

    data class RegisterRequest(
        val fullName: String,
        val email: String,
        val password: String,
        val phone: String
    )

    data class LoginRequest(
        val email: String,
        val password: String
    )

    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Int
    )

    suspend fun register(fullName: String, email: String, password: String, phone: String): Result<String> = try {
        val request = RegisterRequest(fullName, email, password, phone)
        val response = SupabaseClient.post("/rest/v1/rpc/register", request)

        when (response) {
            is ApiResponse.Success -> {
                Result.success("Registration successful. Check your email.")
            }
            is ApiResponse.Error -> {
                Result.failure(Exception("Registration failed: ${response.message}"))
            }
            is ApiResponse.Exception -> {
                Result.failure(response.exception)
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun login(email: String, password: String): Result<String> = try {
        val request = LoginRequest(email, password)
        val response = SupabaseClient.post("/auth/v1/token?grant_type=password", request)

        when (response) {
            is ApiResponse.Success -> {
                val tokenResponse = gson.fromJson(response.data, TokenResponse::class.java)
                tokenManager.saveTokens(tokenResponse.accessToken, tokenResponse.refreshToken)
                Result.success(tokenResponse.accessToken)
            }
            is ApiResponse.Error -> {
                Result.failure(Exception("Login failed: ${response.message}"))
            }
            is ApiResponse.Exception -> {
                Result.failure(response.exception)
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun logout() {
        tokenManager.clearTokens()
    }
}