import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { BackButton } from '../components/ui'
import { LEGAL_PATHS, SUPPORT_EMAIL, type LegalDoc } from '../lib/legal'
import { useT } from '../lib/i18n'

// /iletisim, /kosullar, /gizlilik: the about and legal pages (Google Play needs the privacy policy at a public URL)

const OWNER = 'Zeynep Uğuz'
const UPDATED = '8 Ekim 2026'

function Email() {
  return SUPPORT_EMAIL ? <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a> : <>[DESTEK E-POSTASI]</>
}

const TITLES: Record<LegalDoc, string> = {
  about: 'Hakkımızda ve İletişim',
  terms: 'Kullanım Koşulları',
  privacy: 'Gizlilik Politikası',
}

export function LegalPage({ doc }: { doc: LegalDoc }) {
  const t = useT()
  const note = t('', 'This page is available in Turkish only.')
  return (
    <main className="screen legal">
      <BackButton to="/" />
      <h1 className="t-title">{TITLES[doc]}</h1>
      {note && <p className="t-caption">{note}</p>}
      {doc === 'about' ? <About /> : doc === 'terms' ? <Terms /> : <Privacy />}
      <nav className="legal-links">
        {(Object.keys(TITLES) as LegalDoc[]).filter(d => d !== doc).map(d => (
          <Link key={d} to={LEGAL_PATHS[d]}>{TITLES[d]}</Link>
        ))}
      </nav>
    </main>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return <section className="stack-sm"><h2 className="t-headline">{title}</h2>{children}</section>
}

function About() {
  return (
    <>
      <p>Nomi, Türkiye'nin 81 ilinde bulunduğun yere, saate, hava durumuna, bütçene ve ne kadar yürümek istediğine göre
        gerçek mekanlarla günlük gezi rotası hazırlayan bir şehir asistanıdır. Gezerken yorulduğunda, yağmur başladığında
        ya da programın gerisinde kaldığında rotanı yeniden düzenler; arkadaşlarınla birlikte planlamana da izin verir.</p>
      <p>Nomi mekan, fiyat ya da mesafe uydurmaz. Mekanlar OpenStreetMap ve Overture Maps gibi açık kaynaklardan gelir,
        düzenli olarak güncellenir; bir mekanın hâlâ açık olup olmadığı gerektiğinde Google Places ile kontrol edilir.
        Fiyatı bilinmeyen mekanlar "fiyat bilgisi yok" olarak gösterilir ve tahmini tutarlara eklenmez.</p>
      <p>Ana sayfa, hava durumu, keşfet ve mekan sayfaları ücretsizdir. Asistan, kişisel rota oluşturma ve değiştirme
        Nomi Premium ile kullanılır.</p>
      <Section title="İletişim">
        <p>Görüş, öneri ve hatalı mekan bildirimleri için: <Email /></p>
        <p>Kapanmış ya da yanlış yerde görünen bir mekanı, mekan sayfasındaki "Bildir" düğmesiyle de bize iletebilirsin.</p>
        <p>Nomi, {OWNER} tarafından geliştirilmektedir.</p>
      </Section>
    </>
  )
}

function Terms() {
  return (
    <>
      <p className="t-caption">Son güncelleme: {UPDATED}</p>
      <p>Nomi'yi kullanarak bu koşulları kabul etmiş olursun. Hizmeti sunan: {OWNER}, iletişim: <Email />.</p>
      <Section title="1. Hizmet">
        <p>Nomi; bulunduğun yere, hava durumuna, bütçene ve tercihlerine göre gezi rotası ve mekan önerileri sunan bir
          şehir asistanıdır. Ana sayfa, hava durumu, keşfet ve mekan sayfaları ücretsizdir. Asistan, kişisel rota
          oluşturma ve değiştirme ile mekan kaydetme için hesap ve aktif bir Nomi Premium paketi gerekir. Bir grup
          planına katılmak ve duraklara oy vermek için yalnızca hesap yeterlidir.</p>
      </Section>
      <Section title="2. Hesap">
        <p>Hesap bilgilerinin doğruluğundan ve şifrenin gizliliğinden sen sorumlusun. Hesabını başkalarıyla
          paylaşamazsın. Otomatik araçlarla (bot, betik) hizmete aşırı istek göndermek yasaktır; bu durumda hesabın
          sınırlandırılabilir veya kapatılabilir.</p>
      </Section>
      <Section title="3. Nomi Premium paketleri">
        <ul>
          <li>Paketler günlük, haftalık, aylık ve yıllık olarak tek seferlik satılır; otomatik yenilenmez.</li>
          <li>Ödeme Google Play üzerinden alınır ve Google Play'in ödeme koşulları geçerlidir. Fiyatlar satın alma
            ekranında gösterilir.</li>
          <li>Süre, ödeme doğrulandığı anda başlar. Süre bitmeden alınan yeni paket mevcut sürenin sonuna eklenir.</li>
          <li>İade talepleri Google Play'in iade politikasına göre Google Play üzerinden yapılır. İade edilen ödemeye ait
            paket sona erer. Kullanılmayan süre için kısmi iade yapılmaz; yasal cayma hakkına ilişkin mevzuat saklıdır.</li>
        </ul>
      </Section>
      <Section title="4. Bilgilerin doğruluğu">
        <p>Mekan konumları, çalışma saatleri ve fiyatlar açık kaynaklardan gelir ve düzenli olarak güncellenir; ancak
          işletmeler bu bilgileri önceden haber vermeden değiştirebilir. Fiyatlar tahminidir ve her mekan için
          bilinmez. Temalı günlerde (ör. düşük bütçe, aile günü) bir mekanın fiyatı veya çocuklara uygunluğu her zaman
          bilinmez. Gitmeden önce mekanın açık olduğunu kontrol etmeni öneririz. Hava durumu tahminleri üçüncü taraf
          kaynaklardan gelir. Önerilere uyup uymamak senin kararındır; yolda trafik ve güvenlik kurallarına dikkat et.</p>
      </Section>
      <Section title="5. Grup planları">
        <p>Bir rotayı davet bağlantısıyla paylaşırsan, bağlantıyı alan ve gruba katılan kişiler rotayı, durakları ve
          gruptakilerin görünen adlarını görür. Bağlantıyı istediğin zaman kapatabilirsin; katılanlar gruptan
          ayrılabilir. Davet bağlantısını yalnızca tanıdığın kişilerle paylaş.</p>
      </Section>
      <Section title="6. Kullanıcı içerikleri">
        <ul>
          <li>Giriş yapmış kullanıcılar mekanlara veya bölgelere fotoğraf ekleyebilir, mekan bildirebilir ve uygulama
            için öneri gönderebilir. Fotoğraflar yayınlanmadan önce otomatik olarak kontrol edilir: fotoğrafın (ya da
            yükleme anında cihazının) konumu mekana yakın olmalı ve fotoğraf, yapay zekâ destekli bir içerik
            kontrolünde o mekanı gösteriyor ve yayın kurallarına uygun bulunmalıdır. Bir fotoğrafı yayınlama
            zorunluluğumuz yoktur.</li>
          <li>Her mekan ve bölge için yalnızca en son onaylanan 10 fotoğraf saklanır; daha eskileri kendiliğinden silinir.</li>
          <li>Yalnızca kendi çektiğin ya da yayınlama hakkına sahip olduğun fotoğrafları yükleyebilirsin. Fotoğraf
            yükleyerek Nomi'ye, bu fotoğrafı uygulamada ve web sitesinde ilgili mekan veya bölge sayfasında gösterme
            amacıyla ücretsiz, münhasır olmayan, dünya çapında ve sen silene kadar süren bir kullanım izni verirsin.
            Fotoğrafın telif hakkı sende kalır. Yayınlanan fotoğrafın yanında adının yalnızca ilk kısmı görünür.</li>
          <li>Yasak içerik: çıplaklık veya müstehcenlik, şiddet, nefret söylemi, yasa dışı faaliyetler, başkalarına ait
            kişisel veriler (kimlik, plaka, ekran), insanların ön planda olduğu fotoğraflar, ekran görüntüleri, reklam
            ve başkasına ait telifli görseller. Küfür ve hakaret içeren öneriler gönderilmez. Kurallara aykırı içerik
            silinir; tekrarında hesap sınırlandırılabilir.</li>
          <li>Bir fotoğrafın kaldırılmasını istiyorsan (ör. seni gösteriyorsa ya da hakkını ihlal ediyorsa) <Email />
            adresine yazabilirsin.</li>
        </ul>
      </Section>
      <Section title="7. Fikri haklar">
        <p>Uygulamanın tasarımı ve yazılımı {OWNER}'a aittir. Harita ve mekan verileri © OpenStreetMap katkıcıları
          (ODbL) ve Overture Maps Foundation; mekan fotoğrafları Wikimedia Commons'tan kendi lisanslarıyla gösterilir.
          Diğer üçüncü taraf veriler kendi lisanslarına tabidir.</p>
      </Section>
      <Section title="8. Sorumluluğun sınırlandırılması">
        <p>Hizmet "olduğu gibi" sunulur. Yürürlükteki mevzuatın izin verdiği ölçüde; önerilere dayanarak yapılan
          harcamalardan, mekanların kapalı olmasından veya hizmetteki geçici kesintilerden doğan dolaylı zararlardan
          sorumluluk kabul edilmez.</p>
      </Section>
      <Section title="9. Hesabın sona ermesi">
        <p>Hesabını uygulamada Profil → Hesabımı sil ile istediğin zaman silebilirsin. Bu koşulların ağır ihlali
          halinde hesap kapatılabilir.</p>
      </Section>
      <Section title="10. Değişiklikler ve uygulanacak hukuk">
        <p>Koşullar güncellenirse yeni hali bu sayfada yayınlanır. Bu koşullara Türkiye Cumhuriyeti hukuku uygulanır;
          tüketici işlemlerinde tüketici hakem heyetleri ve tüketici mahkemeleri yetkilidir.</p>
      </Section>
    </>
  )
}

function Privacy() {
  return (
    <>
      <p className="t-caption">Son güncelleme: {UPDATED}</p>
      <p>Bu politika, Nomi'nin hangi kişisel verileri neden işlediğini, kimlerle paylaştığını ve haklarını nasıl
        kullanabileceğini açıklar. Veri sorumlusu: {OWNER}, iletişim: <Email />. Kişisel veriler 6698 sayılı
        Kişisel Verilerin Korunması Kanunu'na (KVKK) uygun olarak işlenir.</p>
      <Section title="1. Topladığımız veriler">
        <ul>
          <li><strong>Hesap bilgileri:</strong> e-posta adresin, adın ve şifren. Şifren yalnızca geri çevrilemez
            biçimde (hash) saklanır.</li>
          <li><strong>Tercihler:</strong> ilgi alanların, bütçen, kişi sayın ve yürüyüş tercihin.</li>
          <li><strong>Konum:</strong> izin verirsen yakındaki mekanları göstermek ve rota planlamak için cihazının konumu
            kullanılır. Konum sürekli izlenmez; yalnızca uygulama açıkken ve bir istek yaptığında gönderilir.
            Oluşturduğun rotaların başlangıç noktası rotayla birlikte saklanır.</li>
          <li><strong>Uygulama içeriği:</strong> oluşturduğun rotalar, kaydettiğin mekanlar, asistana yazdığın
            mesajlar, grup planlarındaki üyeliklerin ve oyların, mekan bildirimlerin ve gönderdiğin öneriler.</li>
          <li><strong>Fotoğraflar:</strong> bir mekana veya bölgeye fotoğraf eklersen fotoğrafın ve, izin verirsen,
            yükleme anındaki cihaz konumun. Fotoğrafın içindeki konum ve tarih ile cihaz konumu yalnızca fotoğrafın
            gerçekten orada çekildiğini doğrulamak için kullanılır; konumun kendisi saklanmaz, yalnızca doğrulamanın
            sonucu saklanır. Fotoğraf sunucuda yeniden kaydedilir ve içindeki tüm üst veriler silinir.</li>
          <li><strong>Satın alma kayıtları:</strong> aldığın paket, süresi, fiyatı ve Google Play sipariş numarası.
            Kart bilgilerin bize ulaşmaz; ödemeyi Google Play alır.</li>
          <li><strong>Teknik kayıtlar:</strong> güvenlik ve kötüye kullanımı önleme amacıyla IP adresi ve istek
            kayıtları kısa süreli tutulur.</li>
        </ul>
        <p>Durak hatırlatıcıları telefonunda yerel bildirim olarak kurulur; bunun için sunucuya ek veri gönderilmez.</p>
      </Section>
      <Section title="2. Verileri neden kullanıyoruz">
        <ul>
          <li>Hesabını oluşturmak, giriş yapmanı ve şifreni sıfırlamanı sağlamak,</li>
          <li>rota ve mekan önerileri sunmak, rotanı hava durumuna, saate ve isteklerine göre yeniden düzenlemek,</li>
          <li>grup planlarında rotayı ve oyları gruptakilere göstermek,</li>
          <li>yüklediğin fotoğrafları yayınlamadan önce doğrulamak ve onaylananları göstermek,</li>
          <li>mekan bildirimlerini ve önerileri değerlendirmek, kapanmış mekanları kaldırmak,</li>
          <li>satın aldığın paketi doğrulamak, süresini yönetmek ve iadeleri işlemek,</li>
          <li>hizmetin güvenliğini sağlamak ve kötüye kullanımı engellemek.</li>
        </ul>
        <p>Verilerin reklam amacıyla kullanılmaz ve satılmaz.</p>
      </Section>
      <Section title="3. Paylaştığımız hizmet sağlayıcılar">
        <ul>
          <li><strong>OpenAI:</strong> asistana yazdığın mesajın metni ve rotandaki mekan adları, mesajın anlamını
            çıkarmak için gönderilir; e-posta adresin, adın ve konumun gönderilmez. Fotoğraf yüklersen, fotoğrafın
            küçültülmüş ve üst verileri silinmiş bir kopyası mekanın adıyla birlikte içerik kontrolü için gönderilir.</li>
          <li><strong>Google Places:</strong> bir mekanın hâlâ açık olup olmadığını kontrol etmek için mekanın adı ve
            konumu gönderilir; senin hesap bilgilerin veya konumun gönderilmez.</li>
          <li><strong>Google Play:</strong> satın almaların doğrulanması ve iadelerin takibi için.</li>
          <li><strong>Open-Meteo:</strong> hava durumu için konum (enlem/boylam) gönderilir; hesap bilgisi gönderilmez.</li>
          <li><strong>Harita sağlayıcısı:</strong> cihazın harita görsellerini doğrudan bu sağlayıcıdan indirir.</li>
          <li><strong>E-posta gönderim hizmeti:</strong> şifre sıfırlama kodunu e-posta adresine göndermek için.</li>
          <li><strong>Sunucu altyapı sağlayıcısı:</strong> verilerin saklandığı altyapı.</li>
        </ul>
        <p>Bu sağlayıcıların bir kısmı yurt dışında bulunduğundan veriler, KVKK'nın yurt dışına aktarım hükümlerine
          uygun olarak aktarılabilir.</p>
      </Section>
      <Section title="4. Saklama süresi ve hesap silme">
        <p>Verilerin hesabın açık olduğu sürece saklanır. Uygulamada Profil → Hesabımı sil ile hesabını istediğin zaman
          silebilirsin; hesabınla birlikte rotaların, grup üyeliklerin ve oyların, kaydettiklerin, mesajların,
          tercihlerin, fotoğrafların, bildirimlerin ve satın alma kayıtların kalıcı olarak silinir. Gönderdiğin öneriler
          hesabından ayrılarak saklanabilir. Google Play'in tuttuğu sipariş kayıtları Google'ın kendi politikalarına
          tabidir. Uygulamaya erişemiyorsan silme talebini <Email /> adresine gönderebilirsin.</p>
        <p>Her mekan ve bölge için yalnızca en son onaylanan 10 fotoğraf saklanır. Reddedilen fotoğrafların dosyaları
          7 gün, sonuç kayıtları 30 gün sonra silinir.</p>
      </Section>
      <Section title="5. Güvenlik">
        <p>Bağlantılar HTTPS ile şifrelenir, şifreler hash'lenerek saklanır ve sunucu erişimi sınırlandırılır. Hiçbir
          sistem tamamen risksiz değildir; bir ihlal durumunda yasal süreler içinde bilgilendirme yapılır.</p>
      </Section>
      <Section title="6. Çocuklar">
        <p>Nomi 13 yaşından küçük çocuklara yönelik değildir ve bilerek bu yaştaki çocuklardan veri toplamaz.</p>
      </Section>
      <Section title="7. Hakların">
        <p>KVKK'nın 11. maddesi kapsamında verilerinin işlenip işlenmediğini öğrenme, bilgi isteme, düzeltilmesini veya
          silinmesini isteme ve itiraz etme hakların vardır. Taleplerini <Email /> adresine iletebilirsin; en geç 30 gün
          içinde yanıtlanır.</p>
      </Section>
      <Section title="8. Değişiklikler">
        <p>Bu politika güncellenirse yeni hali bu sayfada yayınlanır ve önemli değişiklikler uygulama içinde duyurulur.</p>
      </Section>
    </>
  )
}
