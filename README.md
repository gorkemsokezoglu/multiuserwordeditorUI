# Çok Kullanıcılı Metin Editörü - UI

Bu proje, çok kullanıcılı metin editörünün kullanıcı arayüzü (UI) bileşenidir. Java Swing kullanılarak geliştirilmiştir ve MTP (Multi-user Text Protocol) protokolü üzerinden sunucu ile haberleşir.

## Özellikler

- Kullanıcı kaydı ve girişi
- Doküman oluşturma ve açma
- Eş zamanlı metin düzenleme
- Otomatik kaydetme

## Gereksinimler

- Java 11 veya üzeri
- Maven

## Derleme ve Çalıştırma

Projeyi derlemek için:

```bash
mvn clean package
```

Çalıştırmak için:

```bash
java -jar target/multiuserwordeditor-ui-1.0-SNAPSHOT.jar
```

## Kullanım

1. Uygulamayı başlatın
2. Sunucu adresi, kullanıcı adı ve şifre girin
3. "Giriş Yap" butonuna tıklayın
4. Yeni doküman oluşturun veya mevcut bir dokümanı açın
5. Düzenlemeye başlayın!

## Protokol

MTP protokolü, sunucu ile istemci arasındaki iletişimi sağlar. Mesaj formatı:

```
TYPE|USER_ID|FILE_ID|DATA_FIELD1:VALUE1,FIELD2:VALUE2|TIMESTAMP
```

Örnek mesajlar:

- `LOGIN|null|null|username:user1,password:pass123|1623456789`
- `FILE_CREATE|user_123|null|name:mydoc.txt|1623456790`
- `TEXT_INSERT|user_123|file_456|position:10,text:Hello|1623456791`

## Lisans

Bu proje MIT lisansı altında lisanslanmıştır.
