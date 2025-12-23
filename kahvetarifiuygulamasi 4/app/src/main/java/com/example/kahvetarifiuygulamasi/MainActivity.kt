@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.kahvetarifiuygulamasi

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/* ===================== MODEL ===================== */

enum class Temp { SICAK, SOGUK }
// Temp ile sıcak/soğuk ayrımı yapıyorum. UI filtresi bu alan üzerinden çalışıyor.

enum class Method { ESPRESSO, GRANUL }
// Aynı kahvenin iki farklı hazırlanışını Method ile ayırıyorum.

// Zorluk etiketi: tarifleri daha “ürünleşmiş” gösterir.
// UI’da chip olarak göstermek istersen hazır.
// Şimdilik biz bunu “bilgi satırı” olarak malzemelerin başına otomatik ekliyoruz.
enum class Difficulty { KOLAY, ORTA, ZOR }

data class RecipeVariant(
    val ingredients: List<String>,
    val steps: List<String>,
    val tips: String = "",
    val yieldText: String = "",
    val targetTempC: Int? = null
)
// RecipeVariant: “Yönteme özel” tarif detaylarını kapsar.
// ingredients: o yöntem için gereken malzemeler
// steps: o yöntem için adımlar
// tips: sadece o yönteme özgü kısa ipucu (opsiyonel)
// Bu yapı, tek kahvenin birden fazla hazırlanışını temiz şekilde saklamayı sağlar.

data class CoffeeRecipe(
    val id: String,
    val name: String,
    val temp: Temp,
    val variants: Map<Method, RecipeVariant>,
    val tips: List<String> = emptyList(),

    val serving: String = "1 porsiyon",
    val difficulty: Difficulty = Difficulty.KOLAY,
    val equipment: List<String> = emptyList()
)
// CoffeeRecipe: Uygulamadaki ana domain modeli (tarif nesnesi).
// id: navigation için unique anahtar (ekranler arası sadece id taşıyoruz)
// name: kullanıcıya görünen ad
// temp: sıcak/soğuk sınıflandırması (filtreleme için)
// variants: Method -> RecipeVariant map’i; seçilen yönteme göre doğru tarif gelir
// tips: kahveye dair “genel” ipuçları (yöntemden bağımsız)

/*
variants alanını Map olarak tuttuğumuz için:
UI tarafında “seçilenYöntem” ile direkt variants[seçilenYöntem] çağrılır.
Yeni yöntem eklemek (ör. "FrenchPress") veri eklemek kadar kolay olur.
Kod okunabilirliği artar; if-else karmaşası azalır.
*/

/* ===================== REPOSITORY ===================== */

/*
- Veri kaynağını tek yerde toplar
- Şu an “local list” var; ileride Room / API’ye geçince UI ve ViewModel minimum değişir.
- Test edilebilirliği artırır: veri kaynağı bağımsız hale gelir.
*/

object CoffeeRepository {

    private fun prettyDifficulty(d: Difficulty): String =
        when (d) {
            Difficulty.KOLAY -> "Kolay"
            Difficulty.ORTA -> "Orta"
            Difficulty.ZOR -> "Zor"
        }

    private fun inferEquipment(method: Method, ing: List<String>, steps: List<String>): List<String> {
        val text = (ing + steps).joinToString(" ").lowercase()

        val eq = mutableListOf<String>()

        // Granülde temel ekipman hep aynı
        if (method == Method.GRANUL) {
            eq.add("Kupa/Fincan")
            eq.add("Kaşık")
        }

        // Espresso makinesi gerçekten “shot” vb içerik varsa
        if (text.contains("shot") || text.contains("portafiltre") || text.contains("espressoyu demle") || text.contains("espresso shot")) {
            if (!eq.contains("Espresso makinesi")) eq.add("Espresso makinesi")
        }

        // Süt köpürtme varsa
        if (text.contains("köpürt") || text.contains("mikroköpük") || text.contains("foam")) {
            if (!eq.contains("Süt köpürtücü")) eq.add("Süt köpürtücü")
        }

        // Blender içerenler
        if (text.contains("blender")) {
            if (!eq.contains("Blender")) eq.add("Blender")
        }

        // Cold brew gibi uzun demleme
        if (text.contains("12–18") || text.contains("12-18") || text.contains("saat") && text.contains("demle")) {
            if (!eq.contains("Kavanoz")) eq.add("Kavanoz")
            if (!eq.contains("Filtre/Tülbent")) eq.add("Filtre/Tülbent")
        }

        // Çalkalama/shaker
        if (text.contains("çalkala") || text.contains("shaker")) {
            if (!eq.contains("Shaker/Kavanoz")) eq.add("Shaker/Kavanoz")
        }

        if (eq.isEmpty()) eq.add("Temel mutfak ekipmanı")

        return eq.distinct()
    }

    // (3) Heuristik: zorluk tahmini
    private fun inferDifficulty(method: Method, ing: List<String>, steps: List<String>): Difficulty {
        val text = (ing + steps).joinToString(" ").lowercase()
        return when {
            text.contains("12–18") || text.contains("12-18") || (text.contains("saat") && text.contains("demle")) -> Difficulty.ORTA
            text.contains("blender") -> Difficulty.ORTA
            text.contains("köpürt") || text.contains("mikroköpük") -> Difficulty.ORTA
            method == Method.ESPRESSO && (text.contains("25–30") || text.contains("1:2") || text.contains("öğütüm")) -> Difficulty.ORTA
            else -> Difficulty.KOLAY
        }
    }

    private fun decorateIngredients(method: Method, ing: List<String>, steps: List<String>): List<String> {
        val serving = "1 porsiyon" // her tarife minimum standart bilgi
        val diff = inferDifficulty(method, ing, steps)
        val eq = inferEquipment(method, ing, steps)

        val info = listOf(
            "Porsiyon: $serving",
            "Zorluk: ${prettyDifficulty(diff)}",
            "Ekipman: ${eq.joinToString(", ")}"
        )

        return info + ing
    }


    private fun normalizeEspressoSteps(steps: List<String>): List<String> {
        return steps.map { s ->
            when (s.trim()) {
                "Espresso." -> "Espresso shot’u hazırla."
                "Espressoyu demle." -> "Espresso shot’u hazırla (taze çekim tercih)."
                else -> s
            }
        }
    }

    private fun normalizeGranulSteps(steps: List<String>): List<String> {
        val joined = steps.joinToString(" ").lowercase()

        val hasBaseAlready =
            joined.contains("baz") || joined.contains("tamamen çöz") || joined.contains("çöz") && joined.contains("ılık")

        val replaced = steps.flatMap { s ->
            val t = s.trim()
            val lower = t.lowercase()

            if (lower.startsWith("granülü erit")) {
                listOf(
                    "Granülü 30 ml ılık/sıcak suda tamamen çöz (baz oluştur).",
                    "Baz hazır olunca tarifin kalan adımlarına geç."
                )
            } else {
                listOf(t)
            }
        }

        return if (!hasBaseAlready && replaced.none { it.lowercase().contains("baz oluştur") }) {
            listOf("Öneri: Granülü önce 30 ml ılık/sıcak suda tamamen çöz (topaklanmayı azaltır).") + replaced
        } else {
            replaced
        }
    }

    private fun normalizeVariantTips(tips: String): String {
        val t = tips.trim()
        if (t.isBlank()) return t

        val lower = t.lowercase()
        return if (lower.contains("ekşi") && lower.contains("acı")) {

            "Ekşi geldiyse: süreyi biraz uzat / öğütümü incelt.  Acı geldiyse: süreyi kısalt / öğütümü kalınlaştır."
        } else {
            t
        }
    }

    // Küçük yardımcılar: Espresso ve Granül varyantlarını kısa yazmak için “pair builder”.
    // mapOf( e(...), g(...) ) ile okunabilir bir DSL gibi kullanıyoruz.
    private fun e(ing: List<String>, steps: List<String>, tips: String = "") =
        Method.ESPRESSO to RecipeVariant(
            ingredients = decorateIngredients(Method.ESPRESSO, ing, steps),
            steps = normalizeEspressoSteps(steps),
            tips = normalizeVariantTips(tips)
        )

    private fun g(ing: List<String>, steps: List<String>, tips: String = "") =
        Method.GRANUL to RecipeVariant(
            ingredients = decorateIngredients(Method.GRANUL, ing.map {
                if (it.lowercase().contains("30 ml su")) "30 ml ılık/sıcak su (baz için)" else it
            }, steps),
            steps = normalizeGranulSteps(steps),
            tips = normalizeVariantTips(tips)
        )

    val recipes: List<CoffeeRecipe> = listOf(

        /* --------- SICAK: Espresso bazlı --------- */
        CoffeeRecipe(
            id = "espresso",
            name = "Espresso",
            temp = Temp.SICAK,
            variants = mapOf(
                e(
                    listOf("18–20 g ince öğütüm kahve"),
                    listOf("25–30 sn’de ~36–40 g shot al (1:2)."),
                    "Tat ekşiyse süreyi uzat, acıysa kısalt."
                ),
                g(
                    listOf("2–3 tk granül", "30–40 ml sıcak su"),
                    listOf("Granülü az suyla yoğun çözelti yap, küçük fincanda iç.")
                )
            ),
            tips = listOf(
                "Taze kavrum + 7–14 gün dinlenme genelde ideal.",
                "Çıkış oranını sabitle (1:2), tadı öğütümle ince ayarla."
            )
        ),

        CoffeeRecipe(
            id = "doppio",
            name = "Doppio",
            temp = Temp.SICAK,
            variants = mapOf(
                e(listOf("18–20 g x2 portafiltre"), listOf("25–30 sn’de ~60–80 g çift shot.")),
                g(listOf("4–5 tk granül", "60–80 ml sıcak su"), listOf("Granülü çöz ve yoğun çift içim hazırla."))
            ),
            tips = listOf("Gövde yüksek olmalı; asidite rahatsız ederse öğütümü tık kalınlaştır.")
        ),

        CoffeeRecipe(
            id = "ristretto",
            name = "Ristretto",
            temp = Temp.SICAK,
            variants = mapOf(
                e(listOf("18–20 g ince öğütüm"), listOf("Kısa çıkış: 15–25 g; tat yoğun ve tatlımsı.")),
                g(listOf("2–3 tk granül", "20–30 ml su"), listOf("Çok az suyla yoğun mini içim."))
            ),
            tips = listOf("Kısa çıkış karamelleşmeyi öne çıkarır; bitterlik yerine tatlı gövde.")
        ),

        CoffeeRecipe(
            id = "lungo",
            name = "Lungo",
            temp = Temp.SICAK,
            variants = mapOf(
                e(listOf("18–20 g ince öğütüm"), listOf("Uzun çıkış: ~80–110 g; aşırı acılıkta öğütümü kalınlaştır.")),
                g(listOf("2 tk granül", "200 ml sıcak su"), listOf("Granülü suda erit, uzun kahve olarak servis."))
            ),
            tips = listOf("Aşırı uzatmak bitterliği artırır; 80–100 g makul sınır.")
        ),

        CoffeeRecipe(
            id = "americano",
            name = "Americano",
            temp = Temp.SICAK,
            variants = mapOf(
                e(listOf("1 shot espresso", "120–150 ml sıcak su"), listOf("Bardağa önce su, sonra espresso.")),
                g(listOf("2–3 tk granül", "180–220 ml sıcak su"), listOf("Oranı 1:10–1:15 aralığında dene."))
            ),
            tips = listOf("Önce su sonra espresso dökmek crema’yı korur.", "Filtre su tat profilini iyileştirir.")
        ),

        CoffeeRecipe(
            id = "cappuccino",
            name = "Cappuccino",
            temp = Temp.SICAK,
            variants = mapOf(
                e(
                    listOf("1 shot espresso", "150 ml süt"),
                    listOf("Espressoyu demle.", "Sütü yoğun köpürt.", "1/3 espresso + 1/3 süt + 1/3 köpük.")
                ),
                g(
                    listOf("2 tk granül", "30 ml su", "150 ml süt"),
                    listOf("Granülü erit.", "Sütü köpürt.", "Kahve üstüne süt ve köpük.")
                )
            ),
            tips = listOf("Köpük ‘kuruya yakın’ olmalı; kaşıkta taşınabilir doku.", "Fincanı ısıtmak sıcaklık kaybını azaltır.")
        ),

        CoffeeRecipe(
            id = "latte",
            name = "Latte",
            temp = Temp.SICAK,
            variants = mapOf(
                e(
                    listOf("1 shot espresso", "220 ml süt"),
                    listOf("Espresso.", "Sütü 60–65°C mikroköpük yap.", "Kahvenin üstüne sütü dök.")
                ),
                g(
                    listOf("2 tk granül", "30 ml su", "220 ml süt"),
                    listOf("Granülü az suyla erit.", "Sütü ısıt/köpürt.", "Bardağa kahve + süt.")
                )
            ),
            tips = listOf("Süt 60–65°C aralığında en tatlı halini verir.", "Mikroköpük doku latte art için idealdir.")
        ),

        CoffeeRecipe(
            id = "flat_white",
            name = "Flat White",
            temp = Temp.SICAK,
            variants = mapOf(
                e(
                    listOf("Kısa shot/ristretto", "120–140 ml süt"),
                    listOf("Shot hazırla.", "Sütü ince mikroköpük yap.", "Düşük yükseklikten dök.")
                ),
                g(
                    listOf("2 tk granül", "30 ml su", "120–140 ml süt"),
                    listOf("Granülü erit.", "Sütü mikroköpük yap.", "Birleştir.")
                )
            ),
            tips = listOf("Latte’ye göre daha az süt → daha yoğun kahve tadı.", "Ristretto gövdeyi artırır.")
        ),

        CoffeeRecipe(
            id = "macchiato",
            name = "Macchiato",
            temp = Temp.SICAK,
            variants = mapOf(
                e(listOf("1 shot espresso", "1–2 YK süt köpüğü"), listOf("Espresso üstüne 1–2 kaşık köpük koy.")),
                g(listOf("2 tk granül", "30 ml su", "1–2 YK süt köpüğü"), listOf("Granülü erit, köpük ekle."))
            ),
            tips = listOf("Köpük miktarı tadı hızla değiştirir; azla başla.", "Küçük fincan ısı kaybını azaltır.")
        ),

        CoffeeRecipe(
            id = "cortado",
            name = "Cortado",
            temp = Temp.SICAK,
            variants = mapOf(
                e(
                    listOf("1 shot espresso", "30–50 ml sıcak süt (az köpük)"),
                    listOf("Espresso.", "Az köpüklü sütle 1:1’e yakın ‘kes’.")
                ),
                g(
                    listOf("2 tk granül", "30 ml su", "30–50 ml sıcak süt"),
                    listOf("Granülü erit, az süt ekle.")
                )
            ),
            tips = listOf("Köpük minimal; pürüzsüz doku hedefle.", "1:1 oran dengeli başlangıç.")
        ),

        CoffeeRecipe(
            id = "mocha",
            name = "Mocha",
            temp = Temp.SICAK,
            variants = mapOf(
                e(
                    listOf("1 shot espresso", "200 ml süt", "20–25 g çikolata/kakao"),
                    listOf("Espresso + çikolatayı karıştır.", "Köpürtülmüş süt ekle.")
                ),
                g(
                    listOf("2 tk granül", "30 ml su", "200 ml süt", "20–25 g çikolata/kakao"),
                    listOf("Granülü erit, çikolatayla karıştır.", "Sütü ekle/köpürt.")
                )
            ),
            tips = listOf("Kakao kullanıyorsan önce az suyla macun yap; topaklanmaz.", "Süt 60–65°C çikolata aromasını belirginleştirir.")
        ),

        CoffeeRecipe(
            id = "espresso_macchiato",
            name = "Espresso Macchiato",
            temp = Temp.SICAK,
            variants = mapOf(
                e(listOf("1 shot espresso", "1–2 YK süt köpüğü"), listOf("Espressoyu ‘lekele’.")),
                g(listOf("2 tk granül", "30 ml su", "1–2 YK süt köpüğü"), listOf("Granülü erit, köpük ekle."))
            ),
            tips = listOf("Küçük miktar köpük aromayı yuvarlar; fazla kaçma.")
        ),

        CoffeeRecipe(
            id = "latte_macchiato",
            name = "Latte Macchiato",
            temp = Temp.SICAK,
            variants = mapOf(
                e(
                    listOf("1 shot espresso", "250 ml süt (köpüklü)"),
                    listOf("Bardağa süt + köpük.", "Üstten espressoyu dök (katmanlı görünüm).")
                ),
                g(
                    listOf("2 tk granül", "30 ml su", "250 ml süt (köpüklü)"),
                    listOf("Granülü erit.", "Süt+köpük, üstten granül kahve.")
                )
            ),
            tips = listOf("Yavaş döküm katmanları korur.", "Sıcaklık farkı katmanı güçlendirir.")
        ),

        CoffeeRecipe(
            id = "affogato",
            name = "Affogato",
            temp = Temp.SICAK,
            variants = mapOf(
                e(listOf("1 shot espresso", "1 top vanilyalı dondurma"), listOf("Dondurma üstüne sıcak espresso dök.")),
                g(listOf("2 tk granül", "30 ml su", "1 top vanilyalı dondurma"), listOf("Granülü erit, dondurma üstüne dök."))
            ),
            tips = listOf("Espressoyu çok bekletmeden dök; sıcak-soğuk kontrast önemli.")
        ),

        CoffeeRecipe(
            id = "con_panna",
            name = "Con Panna",
            temp = Temp.SICAK,
            variants = mapOf(
                e(listOf("1 shot espresso", "Çırpılmış krema"), listOf("Espresso üstüne krema ekle.")),
                g(listOf("2 tk granül", "30 ml su", "Krema"), listOf("Granülü erit, üstüne krema."))
            ),
            tips = listOf("Kremayı fazla şekerleme; espresso dengesi korunmalı.")
        ),

        CoffeeRecipe(
            id = "breve",
            name = "Breve",
            temp = Temp.SICAK,
            variants = mapOf(
                e(listOf("1 shot espresso", "Half-and-half (süt+krema)"), listOf("Half-and-half ısıt, espressoya ekle.")),
                g(listOf("2 tk granül", "30 ml su", "Half-and-half"), listOf("Granülü erit, ısıtılmış karışım ekle."))
            ),
            tips = listOf("Çok ağır gelirse half-and-half’i sütle incelt.")
        ),

        CoffeeRecipe(
            id = "marocchino",
            name = "Marocchino",
            temp = Temp.SICAK,
            variants = mapOf(
                e(listOf("1 shot espresso", "Kakao", "Az süt köpüğü"), listOf("Espresso.", "Kakao serp, az köpükle bitir.")),
                g(listOf("2 tk granül", "30 ml su", "Kakao", "Az köpük"), listOf("Granülü erit.", "Kakao+köpük ekle."))
            ),
            tips = listOf("Kakaoyu fincana da serpersen koku etkisi artar.")
        ),

        CoffeeRecipe(
            id = "cafe_bombon",
            name = "Café Bombón",
            temp = Temp.SICAK,
            variants = mapOf(
                e(listOf("1 shot espresso", "Kondanse süt"), listOf("Bardağa yoğun süt, üstüne espresso (katmanlı).")),
                g(listOf("2 tk granül", "30 ml su", "Kondanse süt"), listOf("Granülü erit, kondanse sütle katman yap."))
            ),
            tips = listOf("Çok tatlıdır; küçük bardak ve yavaş içim uygundur.")
        ),

        CoffeeRecipe(
            id = "irish_coffee",
            name = "Irish Coffee",
            temp = Temp.SICAK,
            variants = mapOf(
                e(
                    listOf("1 shot espresso", "60 ml viski", "Şeker", "Krema"),
                    listOf("Espresso+viski+şeker karıştır.", "Üstüne krema ekle.")
                ),
                g(
                    listOf("2 tk granül", "30 ml su", "60 ml viski", "Şeker", "Krema"),
                    listOf("Granülü erit, viski+şeker karıştır.", "Krema ekle.")
                )
            ),
            tips = listOf("Kremayı kaşığın üzerinden dökerek üstte tut.", "Viski aroması çok baskınsa miktarı düşür.")
        ),

        /* --------- SOĞUK: Espresso bazlı --------- */
        CoffeeRecipe(
            id = "iced_americano",
            name = "Iced Americano",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("1 shot espresso", "Buz", "Soğuk su"), listOf("Bardağa buz+su, üstüne espresso.")),
                g(listOf("2 tk granül", "30 ml su", "Buz", "Soğuk su"), listOf("Granülü erit, soğutarak buzlu suyla tamamla."))
            ),
            tips = listOf("Espressoyu hafif soğutup eklersen buz daha az erir.")
        ),

        CoffeeRecipe(
            id = "iced_latte",
            name = "Iced Latte",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("1 shot espresso", "Buz", "220 ml soğuk süt"), listOf("Buz+süt, üstüne espresso.")),
                g(listOf("2 tk granül", "30 ml su", "Buz", "220 ml soğuk süt"), listOf("Granülü erit, soğut; buz+süt üstüne ekle."))
            ),
            tips = listOf("Sütü 4–6°C kullanmak gövdeyi artırır.", "Şurubu önce sütle karıştır; dibe çökme azalır.")
        ),

        CoffeeRecipe(
            id = "iced_mocha",
            name = "Iced Mocha",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("Espresso", "Buz", "Süt", "Çikolata"), listOf("Çikolata+espresso karıştır.", "Buz+süt ekle.")),
                g(listOf("Granül", "30 ml su", "Buz", "Süt", "Çikolata"), listOf("Granül+çikolatayı karıştır.", "Buz+süt ekle."))
            ),
            tips = listOf("Çikolatayı önce az sütle aç; pürüzsüz kıvam.")
        ),

        CoffeeRecipe(
            id = "iced_cappuccino",
            name = "Iced Cappuccino",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("Espresso", "Buz", "Süt", "Soğuk süt köpüğü"), listOf("Buz+süt.", "Üstüne espresso ve köpük.")),
                g(listOf("Granül", "30 ml su", "Buz", "Süt", "Soğuk köpük"), listOf("Granülü erit, buz+süt+kıvamlı köpük ekle."))
            ),
            tips = listOf("Soğuk süt köpüğü için sütü 2–4°C’de köpürt.")
        ),

        CoffeeRecipe(
            id = "iced_macchiato",
            name = "Iced Macchiato",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("Süt+buz", "Üste espresso"), listOf("Bardağa buz+süt.", "Üstten espressoyu yavaşça dök.")),
                g(listOf("Buz+süt", "Üste granül çözelti"), listOf("Granülü erit, soğut; buz+süt üzerine dök."))
            ),
            tips = listOf("Yavaş döküm katman görünümünü korur.")
        ),

        CoffeeRecipe(
            id = "cold_brew",
            name = "Cold Brew",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("60 g kalın öğütüm", "1 L soğuk su"), listOf("12–18 saat demle, filtrele.")),
                g(listOf("2–3 tk granül", "250 ml soğuk su/süt", "Buz"), listOf("Granülü az ılık suda erit, soğuk sıvı + buzla tamamla."))
            ),
            tips = listOf("1:15–1:17 oran iyi başlangıç.", "Kaba öğütüm; toz çoksa acılık artar.")
        ),

        CoffeeRecipe(
            id = "nitro_cold_brew",
            name = "Nitro Cold Brew",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("Cold brew", "Nitro infüzyon"), listOf("Cold brew’ü azot ile infüze et.")),
                g(listOf("Granül baz", "Nitro (varsa)"), listOf("Granül bazını hazırla, nitro tap ile servis."))
            ),
            tips = listOf("Nitro ipeksi doku verir; buz miktarını düşük tut.")
        ),

        CoffeeRecipe(
            id = "iced_flat_white",
            name = "Iced Flat White",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("Kısa shot", "Buz", "Süt"), listOf("Buz+süt, kısa shot ekle.")),
                g(listOf("Yoğun granül", "Buz", "Süt"), listOf("Granülü az suyla yoğunlaştır, buz+sütle birleştir."))
            ),
            tips = listOf("Kısa shot aromayı korur; buz erimesine dikkat.")
        ),

        CoffeeRecipe(
            id = "iced_espresso",
            name = "Iced Espresso",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("1–2 shot espresso", "Buz"), listOf("Espressoyu soğut, buz üzerinde servis.")),
                g(listOf("Yoğun granül çözelti", "Buz"), listOf("Granülü az suyla erit, buz üstünde servis."))
            ),
            tips = listOf("Sıcak espressoyu doğrudan buza dökme; tadı seyrelir.")
        ),

        CoffeeRecipe(
            id = "shakerato",
            name = "Shakerato",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("Espresso", "Buz", "Şurup"), listOf("Shaker’da buz+espresso+şurubu çalkala, süz.")),
                g(listOf("Granül çözelti", "Buz", "Şurup"), listOf("Granül bazını hazırla, buzla iyice çalkala."))
            ),
            tips = listOf("İyice çalkala; üstte ince, kremsi köpük oluşsun.")
        ),

        CoffeeRecipe(
            id = "freddo_espresso",
            name = "Freddo Espresso",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("1–2 shot espresso", "Buz", "Az şeker"), listOf("Buzla çalkala, süz.")),
                g(listOf("Yoğun granül", "Buz", "Az şeker"), listOf("Granülü az suyla çözüp buzla çalkala, süz."))
            ),
            tips = listOf("Şeker istiyorsan çalkalamadan önce ekle; daha iyi çözünür.")
        ),

        CoffeeRecipe(
            id = "freddo_cappuccino",
            name = "Freddo Cappuccino",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("Freddo espresso", "Soğuk süt köpüğü"), listOf("Freddo espresso üzerine soğuk süt köpüğü ekle.")),
                g(listOf("Granül baz", "Soğuk süt köpüğü"), listOf("Granülü çalkala, üstüne soğuk süt köpüğü."))
            ),
            tips = listOf("Soğuk süt köpüğü için yağ oranı %3+ süt tercih edilebilir.")
        ),

        CoffeeRecipe(
            id = "espresso_tonic",
            name = "Espresso Tonic",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("Buz", "Tonik", "Espresso"), listOf("Bardağa buz+tonik, üstten espressoyu dök.")),
                g(listOf("Buz", "Tonik", "Granül baz"), listOf("Buz+tonik, üstten granül bazını dök."))
            ),
            tips = listOf("Narenciye kabuğu ile ferahlığı artır.", "Aşırı acılıkta ‘light’ tonik dene.")
        ),

        CoffeeRecipe(
            id = "affogato_freddo",
            name = "Affogato Freddo",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("Soğutulmuş espresso", "Dondurma"), listOf("Dondurma üstüne soğuk espresso.")),
                g(listOf("Soğuk granül baz", "Dondurma"), listOf("Dondurma üstüne soğuk granül kahve."))
            ),
            tips = listOf("Bardağı önceden soğutmak erimeyi yavaşlatır.")
        ),

        CoffeeRecipe(
            id = "iced_caramel_latte",
            name = "Iced Caramel Latte",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("Espresso", "Buz", "Süt", "Karamel şurubu"), listOf("Buz+süt+karamel, üstüne espresso.")),
                g(listOf("Granül baz", "Buz", "Süt", "Karamel şurubu"), listOf("Granülü erit, buz+süt+karamel ile karıştır."))
            ),
            tips = listOf("Şurubu önce sütle karıştır; dibe çökmesin.")
        ),

        CoffeeRecipe(
            id = "iced_vanilla_latte",
            name = "Iced Vanilla Latte",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("Espresso", "Buz", "Süt", "Vanilya şurubu"), listOf("Buz+süt+vanilya, üstüne espresso.")),
                g(listOf("Granül baz", "Buz", "Süt", "Vanilya şurubu"), listOf("Granülü erit, buz+süt+vanilya ile karıştır."))
            ),
            tips = listOf("Vanilya şurubu çok tatlıysa süt oranını artır.")
        ),

        CoffeeRecipe(
            id = "mocha_frappe",
            name = "Mocha Frappe",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(
                    listOf("Espresso", "Süt", "Çikolata", "Buz", "Blender"),
                    listOf("Tüm malzemeleri blender’da çek.")
                ),
                g(
                    listOf("Granül", "Süt", "Çikolata", "Buz", "Blender"),
                    listOf("Granül bazla blender’da pürüzsüz kıvam elde et.")
                )
            ),
            tips = listOf("Buzu kademeli ekle; kıvamı daha iyi kontrol edersin.")
        ),

        CoffeeRecipe(
            id = "espresso_frappe",
            name = "Espresso Frappe",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(
                    listOf("Espresso", "Süt/su", "Buz", "Şeker (ops.)", "Blender"),
                    listOf("Hepsini blender’da köpüklü kıvam olana kadar karıştır.")
                ),
                g(
                    listOf("Granül", "Süt/su", "Buz", "Şeker (ops.)", "Blender"),
                    listOf("Granülle blender’da köpüklü kıvam elde et.")
                )
            ),
            tips = listOf("Şeker kullanacaksan blender öncesi ekle; iyi çözünür.")
        ),

        CoffeeRecipe(
            id = "iced_breve",
            name = "Iced Breve",
            temp = Temp.SOGUK,
            variants = mapOf(
                e(listOf("Espresso", "Buz", "Half-and-half"), listOf("Buz üzerine half-and-half, üstüne espresso.")),
                g(listOf("Granül", "Buz", "Half-and-half"), listOf("Granül bazını buz+half-and-half ile birleştir."))
            ),
            tips = listOf("Ağır gelirse half-and-half’i sütle seyrelt.")
        )
    )
}

/* ===================== STATE & VM ===================== */

data class UiState(
    val tempFilter: Temp? = null,
    val list: List<CoffeeRecipe> = CoffeeRepository.recipes
)
// UiState “tek gerçek kaynak”. Ekranın ihtiyacı olan her şey burada: seçili filtre + liste.

class CoffeeViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun setTempFilter(filter: Temp?) {
        _state.update { current ->
            current.copy(
                tempFilter = filter,
                list = CoffeeRepository.recipes.filter { filter == null || it.temp == filter }
            )
        }
    }

    fun getRecipe(id: String): CoffeeRecipe? =
        CoffeeRepository.recipes.find { it.id == id }
}

/* ===================== COLORS ===================== */

private val BackgroundColor = Color(0xFFA4907C)

// Palette listesi kullanma nedeni:
// AnimatedBackground ileride “gradient/animasyonlu geçiş” gibi geliştirmelere açık kalsın diye
private val HotPalette = listOf(BackgroundColor)
private val ColdPalette = listOf(BackgroundColor)
private val DefaultPalette = listOf(BackgroundColor)

private val CardColor = Color(0xFFFFF7EF)
private val CardSurfaceColor = Color(0xFFFFFBF6)
private val ButtonColor = Color(0xFFBCA88D)
private val IconBgColor = Color(0xFFDFD3C3)
private val PillTrackColor = Color(0xFFEFE7DF)

/* ===================== BACKGROUND ===================== */

@Composable
fun AnimatedBackground(modifier: Modifier = Modifier, palette: List<Color>) {
    // Yapıyı palette üzerinden kurmamızın sebebi: ileride renk geçişleri / animasyon eklemeyi kolaylaştırmak.
    val color = palette.firstOrNull() ?: BackgroundColor
    Box(modifier = modifier.background(color))
}

/* ===================== ACTIVITY & NAV ===================== */

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val nav = rememberNavController()
                val vm: CoffeeViewModel = viewModel() // Tek VM: sunumda anlatması kolay

                NavHost(navController = nav, startDestination = "list") {
                    composable("list") {
                        ListScreen(
                            vm = vm,
                            onOpen = { id -> nav.navigate("detail/$id") }
                        )
                    }
                    composable(
                        route = "detail/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val id = backStackEntry.arguments?.getString("id").orEmpty()
                        DetailScreen(
                            recipe = vm.getRecipe(id),
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}

/* ===================== UI: LIST SCREEN ===================== */

@Composable
fun ListScreen(
    vm: CoffeeViewModel,
    onOpen: (String) -> Unit
) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current

    val pal = when (s.tempFilter) {
        Temp.SICAK -> HotPalette
        Temp.SOGUK -> ColdPalette
        else -> DefaultPalette
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedBackground(Modifier.matchParentSize(), pal)

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                HomeHeader(
                    selected = s.tempFilter,
                    onTemp = vm::setTempFilter
                )
            }
        ) { inner ->
            LazyColumn(
                modifier = Modifier
                    .padding(inner)
                    .fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(s.list, key = { it.id }) { r ->
                    val imgRes = coffeeListImageResId(r, context)

                    Surface(
                        color = CardColor,
                        tonalElevation = 3.dp,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .clickable { onOpen(r.id) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                painter = painterResource(id = imgRes),
                                contentDescription = r.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            )

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = r.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = coffeeSubtitle(r),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }

                            Text(
                                text = "›",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ===================== UI: HEADER ===================== */

@Composable
fun HomeHeader(selected: Temp?, onTemp: (Temp?) -> Unit) {
    Surface(color = BackgroundColor) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Coffee Recipes",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.2.sp
                ),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            TempPillRow(selected = selected, onTemp = onTemp)
        }
    }
}

@Composable
private fun TempPillRow(selected: Temp?, onTemp: (Temp?) -> Unit) {
    Surface(
        color = PillTrackColor,
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TempPill(
                text = "Sıcak",
                selected = selected == Temp.SICAK,
                onClick = { onTemp(if (selected == Temp.SICAK) null else Temp.SICAK) },
                modifier = Modifier.weight(1f)
            )
            TempPill(
                text = "Soğuk",
                selected = selected == Temp.SOGUK,
                onClick = { onTemp(if (selected == Temp.SOGUK) null else Temp.SOGUK) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TempPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (selected) ButtonColor else Color.Transparent,
        shape = RoundedCornerShape(999.dp),
        border = if (!selected) BorderStroke(1.dp, ButtonColor) else null,
        modifier = modifier
            .fillMaxHeight()
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = if (selected) Color.White else Color(0xFF2C2C2C),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        }
    }
}

/* ===================== UI: DETAIL SCREEN ===================== */

@Composable
fun DetailScreen(
    recipe: CoffeeRecipe?,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val pal = when (recipe?.temp) {
        Temp.SICAK -> HotPalette
        Temp.SOGUK -> ColdPalette
        else -> DefaultPalette
    }

    var method by remember { mutableStateOf(Method.ESPRESSO) }
    var pratikOpen by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AnimatedBackground(Modifier.matchParentSize(), pal)

        if (recipe == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Tarif bulunamadı.")
            }
            return@Box
        }

        val prefs = remember(context) {
            context.getSharedPreferences("coffee_prefs", Context.MODE_PRIVATE)
        }
        val userKey = "user_recipe_${recipe.id}"

        var userRecipeText by remember(recipe.id) {
            mutableStateOf(prefs.getString(userKey, "") ?: "")
        }

        val meta = recipeMetaFor(recipe.id)

        // Görsel boyutu ve kart üst boşluğu
        val imageSize: Dp = 240.dp
        val overlapIntoCard: Dp = 70.dp

        // Dp bölme riskini kaldırmak için:
        val halfImage = imageSize * 0.5f
        val contentTopSpacer = halfImage + overlapIntoCard + 12.dp

        val imgName = if (recipe.temp == Temp.SICAK) "hot_detail" else "cold_detail"
        val imgRes = safeDrawableResId(imgName, context)

        // ALT KART
        Surface(
            color = CardSurfaceColor,
            shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = halfImage + 26.dp - overlapIntoCard)
        ) {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(contentTopSpacer))

                Text(
                    text = recipe.name,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.2.sp
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⏱ ${meta.minutes} dakika", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(16.dp))
                    Text("🔥 ${meta.calories} kalori", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(6.dp))

                MethodPillSwitch(selected = method, onSelect = { method = it })

                val v = recipe.variants[method]
                if (v == null) {
                    Text("Bu kahve için seçilen yöntem yok.")
                } else {
                    Text("Malzemeler", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    Spacer(Modifier.height(4.dp))
                    v.ingredients.forEach { Text("• $it", fontSize = 18.sp) }

                    Spacer(Modifier.height(14.dp))

                    Text("Hazırlanışı", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    Spacer(Modifier.height(4.dp))
                    v.steps.forEachIndexed { i, s ->
                        Text("${i + 1}. $s", fontSize = 18.sp)
                    }
                }

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = { pratikOpen = !pratikOpen },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ButtonColor,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text("Pratik Bilgiler", fontWeight = FontWeight.SemiBold)
                }

                if (pratikOpen) {
                    Surface(
                        color = PillTrackColor,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (recipe.tips.isNotEmpty()) {
                                Text("Genel İpuçları", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                                recipe.tips.forEach { Text("• $it", fontSize = 17.sp) }
                            }

                            val vTips = recipe.variants[method]?.tips.orEmpty()
                            if (vTips.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text("Yöntem İpucu", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                                Text(vTips, fontSize = 17.sp)
                            }

                            Spacer(Modifier.height(6.dp))

                            Button(
                                onClick = { customOpen = !customOpen },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ButtonColor,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                            ) {
                                Text("Kendi tarifini yaz", fontWeight = FontWeight.SemiBold)
                            }

                            if (customOpen) {
                                OutlinedTextField(
                                    value = userRecipeText,
                                    onValueChange = { userRecipeText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Tarifini buraya yaz") }
                                )

                                Button(
                                    onClick = { prefs.edit().putString(userKey, userRecipeText).apply() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ButtonColor,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                ) {
                                    Text("Kaydet", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }

        // ÜSTTE DAİRE GÖRSEL
        Surface(
            color = IconBgColor,
            shape = CircleShape,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 26.dp)
                .size(imageSize)
        ) {
            Image(
                painter = painterResource(id = imgRes),
                contentDescription = recipe.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
                    .clip(CircleShape)
            )
        }

        TextButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 8.dp, start = 6.dp)
        ) {
            Text("Geri", color = Color(0xFF2C2C2C))
        }
    }
}

/* ===================== UI: METHOD PILL ===================== */

@Composable
private fun MethodPillSwitch(
    selected: Method,
    onSelect: (Method) -> Unit
) {
    Surface(
        color = PillTrackColor,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(6.dp)) {
            PillItem(
                text = "Espresso",
                selected = selected == Method.ESPRESSO,
                onClick = { onSelect(Method.ESPRESSO) },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            PillItem(
                text = "Granül",
                selected = selected == Method.GRANUL,
                onClick = { onSelect(Method.GRANUL) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PillItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (selected) ButtonColor else Color.Transparent,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier
            .height(44.dp)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = if (selected) Color.White else Color(0xFF2C2C2C),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/* ===================== HELPERS ===================== */

// Bu helper’lar UI’daki görsel seçimini veri modeline bağlar.
// Amaç: UI içinde “string isim üretme / fallback” gibi tekrarları azaltmak.
fun coffeeDetailImageResId(recipe: CoffeeRecipe, context: Context): Int {
    val name = if (recipe.temp == Temp.SICAK) "hot_detail" else "cold_detail"
    return context.resources.getIdentifier(name, "drawable", context.packageName)
}

// Liste ekranında önce list görselini dener; yoksa detay görseline düşer.
// Böylece drawable eksik olsa bile uygulama çökmez, kullanıcı boş görmez.
fun coffeeListImageResId(recipe: CoffeeRecipe, context: Context): Int {
    val listName = if (recipe.temp == Temp.SICAK) "hot_list" else "cold_list"
    val listRes = context.resources.getIdentifier(listName, "drawable", context.packageName)
    return if (listRes != 0) listRes else coffeeDetailImageResId(recipe, context)
}

// Alt başlık: temp’e göre kısa açıklama üretir.
fun coffeeSubtitle(recipe: CoffeeRecipe): String =
    when (recipe.temp) {
        Temp.SICAK -> "Sıcak espresso bazlı kahve"
        Temp.SOGUK -> "Soğuk espresso bazlı kahve"
    }

data class RecipeMeta(val minutes: Int, val calories: Int)

fun recipeMetaFor(id: String): RecipeMeta {
    return when (id) {
        "americano" -> RecipeMeta(minutes = 5, calories = 15)
        "iced_americano" -> RecipeMeta(minutes = 5, calories = 15)
        "latte" -> RecipeMeta(minutes = 7, calories = 160)
        "iced_latte" -> RecipeMeta(minutes = 6, calories = 160)
        "espresso" -> RecipeMeta(minutes = 3, calories = 5)
        else -> RecipeMeta(minutes = 6, calories = 80)
    }
}

// Güvenli drawable çözümleme: drawable bulunamazsa sistem ikonuna düşer.
fun safeDrawableResId(name: String, context: Context): Int {
    val res = context.resources.getIdentifier(name, "drawable", context.packageName)
    return if (res != 0) res else android.R.drawable.ic_menu_gallery
}