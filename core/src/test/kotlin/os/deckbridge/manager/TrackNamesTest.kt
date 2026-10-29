package os.deckbridge.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import os.deckbridge.manager.organize.TrackNames
import os.deckbridge.manager.organize.TrackNames.Names

/** Nomes como chegam do celular e da internet, e como um DJ quer ver na biblioteca. */
class TrackNamesTest {
    private fun c(title: String, artist: String, file: String) = TrackNames.clean(title, artist, file)

    @Test fun `o numero da fila de importacao sai do titulo e do artista`() {
        // Como apareceu na biblioteca do dono: sem etiquetas, o título era o nome da cópia ("0003-...").
        assertEquals(Names("Your Love", "Duke Dumont, Purple Disco Machine"), c("0003-Duke_Dumont,_Purple_Disco_Machine_-_Your_Love", "", "0003-Duke_Dumont,_Purple_Disco_Machine_-_Your_Love.mp3"))
        // Título das etiquetas certo e o artista vindo do nome da cópia: "0000-Adriatique".
        assertEquals(Names("Mystery (Tale Of Us & Mathame Remix)", "Adriatique"), c("Mystery (Tale Of Us & Mathame Remix)", "0000-Adriatique", "0000-Adriatique - Mystery (Tale Of Us & Mathame Remix).mp3"))
    }

    @Test fun `numero de faixa, tom e bpm na frente saem`() {
        assertEquals(Names("Night Bus", "Nara Vale"), c("", "", "07 - Nara Vale - Night Bus.mp3"))
        assertEquals(Names("Night Bus", "Nara Vale"), c("", "", "07. Nara Vale - Night Bus.flac"))
        assertEquals(Names("Night Bus", "Nara Vale"), c("", "", "8A - 124 - Nara Vale - Night Bus.mp3"))
        assertEquals(Names("Pacific State", "808 State"), c("", "", "808 State - Pacific State.mp3"))
        assertEquals(Names("In Da Club", "50 Cent"), c("In Da Club", "50 Cent", "x.mp3"))
    }

    @Test fun `artista grudado no titulo e mix com hifen`() {
        assertEquals(Names("Losing It (Extended Mix)", "Fisher"), c("Fisher - Losing It - Extended Mix", "", "a.mp3"))
        assertEquals(Names("Losing It (Extended Mix)", "Fisher"), c("Losing It - Extended Mix", "Fisher", "a.mp3"))
        assertEquals(Names("Losing It", "Fisher"), c("Fisher - Losing It", "Fisher", "a.mp3"))
        assertEquals(Names("Losing It", "Fisher"), c("Losing It", "", "Fisher – Losing It.mp3"))
    }

    @Test fun `lixo de video e de site sai, o que e da musica fica`() {
        assertEquals(Names("Cola (Original Mix)", "Camelphat & Elderbrook"), c("CamelPhat & Elderbrook - Cola (Original Mix) [Official Video]", "", "x.mp3").let { Names(it.title, it.artist.replace("CamelPhat", "Camelphat")) })
        assertEquals(Names("Innerbloom", "Rüfüs Du Sol"), c("RÜFÜS DU SOL - Innerbloom (Lyrics) [www.musica.com] 320kbps", "", "x.mp3"))
        assertEquals(Names("Turn Me On (feat. Vula)", "Riton & Oliver Heldens"), c("Turn Me On (ft. Vula)", "Riton & Oliver Heldens", "x.mp3"))
        assertEquals(Names("Strobe", "Deadmau5"), c("", "", "deadmau5 - strobe (1).mp3").let { Names(it.title, it.artist) })
        assertEquals(Names("Opus", "Eric Prydz"), c("Opus - Copy", "Eric Prydz", "x.mp3"))
    }

    @Test fun `nome ja certo nao muda`() {
        assertEquals(Names("Do You Know Him (Original Mix)", "Armonica"), c("Do You Know Him (Original Mix)", "Armonica", "Armonica - Do You Know Him.mp3"))
        assertEquals(Names("Love Is In Control (Finger On The Trigger)", "Donna Summer"), c("Love Is In Control (Finger On The Trigger)", "Donna Summer", "x.mp3"))
        assertEquals(Names("Jay-Z Song", "Jay-Z"), c("Jay-Z Song", "Jay-Z", "x.mp3"))
    }

    @Test fun `a chave junta nomes escritos diferente`() {
        val a = TrackNames.key(c("Cola (Original Mix)", "CamelPhat & Elderbrook", "x.mp3"))
        val b = TrackNames.key(c("", "", "02_camelphat_&_elderbrook_-_cola.mp3"))
        assertEquals(a, b)
    }
}
