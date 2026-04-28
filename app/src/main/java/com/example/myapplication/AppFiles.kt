package com.example.myapplication

// App completa en un solo archivo para simplificar:
// modelos, Retrofit, repository, UiState, ViewModels, navegación y pantallas.

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// -----------------------------
// MODELOS POKEAPI
// -----------------------------

data class PokemonListResponse(
    val count: Int,
    val next: String?,
    val previous: String?,
    val results: List<PokemonListItem>
)

data class PokemonListItem(
    val name: String,
    val url: String
)

data class PokemonDetailResponse(
    val id: Int,
    val name: String,
    val sprites: Sprites,
    val types: List<PokemonTypeSlot>,
    val height: Int,
    val weight: Int
)

data class Sprites(
    @SerializedName("front_default")
    val frontDefault: String?
)

data class PokemonTypeSlot(
    val slot: Int,
    val type: PokemonType
)

data class PokemonType(
    val name: String
)

// -----------------------------
// RETROFIT
// -----------------------------

interface PokeApiService {

    @GET("pokemon")
    suspend fun getPokemonList(
        @Query("limit") limit: Int,
        @Query("offset") offset: Int
    ): PokemonListResponse

    @GET("pokemon/{name}")
    suspend fun getPokemonDetail(
        @Path("name") name: String
    ): PokemonDetailResponse
}

object RetrofitClient {

    private const val BASE_URL = "https://pokeapi.co/api/v2/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .build()

    val api: PokeApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PokeApiService::class.java)
    }
}

// -----------------------------
// REPOSITORY
// -----------------------------

class PokemonRepository {

    private val api = RetrofitClient.api

    suspend fun getPokemonPage(limit: Int, offset: Int): Result<List<PokemonListItem>> {
        return try {
            val response = api.getPokemonList(limit = limit, offset = offset)
            val list = response.results
            if (list.isEmpty()) {
                Result.success(emptyList())
            } else {
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPokemonDetail(name: String): Result<PokemonDetailResponse> {
        return try {
            val response = api.getPokemonDetail(name)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun extractIdFromUrl(url: String): String {
        return url.trimEnd('/').substringAfterLast('/')
    }
}

// -----------------------------
// UI STATE COMÚN
// -----------------------------

sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    object Empty : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String?) : UiState<Nothing>()
}

// -----------------------------
// VIEWMODEL LISTA
// -----------------------------



class PokemonListViewModel(
    private val repository: PokemonRepository = PokemonRepository()
) : ViewModel() {
    private  val PAGE_SIZE = 30
    private  val MAX_PAGES = 3
    private var currentPage = 0


    private val _uiState = MutableStateFlow<UiState<List<PokemonListItem>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<PokemonListItem>>> = _uiState

    private var currentOffset = 0
    private var isLoadingMore = false
    private var hasMore = true

    init {
        loadFirstPage()
    }

    fun loadFirstPage() {
        currentOffset = 0
        hasMore = true
        loadPage(reset = true)
    }

    fun loadNextPageIfNeeded() {
        if (isLoadingMore || !hasMore) return
        loadPage(reset = false)
    }

    fun retry() {
        loadFirstPage()
    }

    private fun loadPage(reset: Boolean) {

        if (!reset && currentPage >= MAX_PAGES) return

        viewModelScope.launch {

            if (reset) {
                currentOffset = 0
                currentPage = 0
                _uiState.value = UiState.Loading
            }

            isLoadingMore = true

            try {
                val result = repository.getPokemonPage(PAGE_SIZE, currentOffset)

                result.onSuccess { list ->

                    val currentList = if (reset) {
                        emptyList()
                    } else {
                        (uiState.value as? UiState.Success)?.data ?: emptyList()
                    }

                    val newList = currentList + list
                    _uiState.value = UiState.Success(newList)

                    currentOffset += PAGE_SIZE
                    currentPage++
                }

            } finally {
                isLoadingMore = false
            }
        }
    }


}

// -----------------------------
// VIEWMODEL DETALLE
// -----------------------------

class PokemonDetailViewModel(
    private val repository: PokemonRepository = PokemonRepository()
) : ViewModel() {

    private val _uiState =
        MutableStateFlow<UiState<PokemonDetailResponse>>(UiState.Loading)
    val uiState: StateFlow<UiState<PokemonDetailResponse>> = _uiState

    private var currentName: String? = null

    fun loadPokemon(name: String) {
        currentName = name
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val result = repository.getPokemonDetail(name)
                result
                    .onSuccess { detail ->
                        _uiState.value = UiState.Success(detail)
                    }
                    .onFailure { e ->
                        _uiState.value = UiState.Error(
                            e.message ?: "Error al cargar detalle"
                        )
                    }
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    e.message ?: "Error inesperado"
                )
            }
        }
    }

    fun retry() {
        currentName?.let { loadPokemon(it) }
    }
}

// -----------------------------
// NAVEGACIÓN
// -----------------------------

object NavRoutes {
    const val LIST = "pokemon_list"
    const val DETAIL = "pokemon_detail"
}

@Composable
fun AppNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = NavRoutes.LIST,
        modifier = modifier
    ) {
        composable(route = NavRoutes.LIST) {
            val listViewModel: PokemonListViewModel = viewModel()
            PokemonListScreen(
                viewModel = listViewModel,
                onPokemonClick = { name ->
                    navController.navigate("${NavRoutes.DETAIL}/$name")
                }
            )
        }

        composable(
            route = "${NavRoutes.DETAIL}/{name}",
            arguments = listOf(
                navArgument("name") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val name = backStackEntry.arguments?.getString("name") ?: ""
            val detailViewModel: PokemonDetailViewModel = viewModel()

            LaunchedEffect(name) {
                detailViewModel.loadPokemon(name)
            }

            PokemonDetailScreen(
                viewModel = detailViewModel,
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

// -----------------------------
// PANTALLA LISTA
// -----------------------------

@Composable
fun PokemonListScreen(
    viewModel: PokemonListViewModel,
    onPokemonClick: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    when (state) {
        is UiState.Loading -> LoadingListState()
        is UiState.Error -> ErrorListState(
            message = (state as UiState.Error).message,
            onRetry = { viewModel.retry() }
        )
        is UiState.Empty -> EmptyListState()
        is UiState.Success -> {
            val pokemons = (state as UiState.Success<List<PokemonListItem>>).data
            PokemonListContent(
                pokemons = pokemons,
                onPokemonClick = onPokemonClick,
                onLoadMore = { viewModel.loadNextPageIfNeeded() }
            )
        }
    }
}

@Composable
private fun LoadingListState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorListState(
    message: String?,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message ?: "Error al cargar la lista")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onRetry) {
                Text("Reintentar")
            }
        }
    }
}

@Composable
private fun EmptyListState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "No hay Pokémon para mostrar")
    }
}

@Composable
private fun PokemonListContent(
    pokemons: List<PokemonListItem>,
    onPokemonClick: (String) -> Unit,
    onLoadMore: () -> Unit
) {
    val repository = PokemonRepository()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp)
    ) {

        items(pokemons) { item ->

            val id = repository.extractIdFromUrl(item.url)
            val imageUrl =
                "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/$id.png"

            PokemonListItemCard(
                id = id,
                name = item.name,
                imageUrl = imageUrl,
                onClick = { onPokemonClick(item.name) }
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onLoadMore,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("Cargar siguientes 30")
            }

            Spacer(modifier = Modifier.height(56.dp))
        }
    }
}


@Composable
private fun PokemonListItemCard(
    id: String,
    name: String,
    imageUrl: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = name,
                modifier = Modifier
                    .size(64.dp)
                    .padding(4.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "#$id",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = name.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

// -----------------------------
// PANTALLA DETALLE
// -----------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PokemonDetailScreen(
    viewModel: PokemonDetailViewModel,
    onBackClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detalle Pokémon") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (state) {
                is UiState.Loading -> LoadingDetailState()
                is UiState.Error -> ErrorDetailState(
                    message = (state as UiState.Error).message,
                    onRetry = { viewModel.retry() }
                )
                is UiState.Empty -> ErrorDetailState(
                    message = "No se encontró información",
                    onRetry = { viewModel.retry() }
                )
                is UiState.Success -> {
                    val data = (state as UiState.Success<PokemonDetailResponse>).data
                    PokemonDetailContent(data)
                }
            }
        }
    }
}

@Composable
private fun LoadingDetailState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorDetailState(
    message: String?,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message ?: "Error al cargar el detalle")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onRetry) {
                Text("Reintentar")
            }
        }
    }
}

@Composable
private fun PokemonDetailContent(
    pokemon: PokemonDetailResponse
) {
    val imageUrl = pokemon.sprites.frontDefault

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = pokemon.name,
                modifier = Modifier.size(160.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "#${pokemon.id}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = pokemon.name.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            },
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Tipos:",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row {
            pokemon.types.forEach { slot ->
                Text(
                    text = slot.type.name.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                    },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Altura: ${pokemon.height}",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Peso: ${pokemon.weight}",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}


