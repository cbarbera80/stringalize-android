package app.stringalize.example

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.stringalize.Stringalize

class MainActivity : AppCompatActivity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Stringalize.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.greeting).text = getString(R.string.greeting)
        findViewById<TextView>(R.id.files).text = resources.getQuantityString(R.plurals.files, 3, 3)
        findViewById<TextView>(R.id.release).text = "Release in use: ${Stringalize.release ?: "none, bundled strings"}"

        findViewById<ComposeView>(R.id.compose).setContent {
            MaterialTheme {
                Column {
                    Text(stringResource(R.string.greeting), style = MaterialTheme.typography.titleLarge)
                    Text(pluralStringResource(R.plurals.files, 1, 1), style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        findViewById<Button>(R.id.check).setOnClickListener {
            Stringalize.checkForUpdates { downloaded ->
                val message = if (downloaded) "Downloaded. Restart the app to see it." else "Nothing new."
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
