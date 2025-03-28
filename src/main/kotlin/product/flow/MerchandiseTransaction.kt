package product.flow

import com.github.michaelbull.result.*
import common.model.type.primitive.ID
import common.model.type.primitive.NonEmptyString
import product.model.command.merchandise.Merchandise
import product.model.type.Product
import product.model.readmodel.ReadProductNames
import product.model.readmodel.ReadDisplayOrders
import product.model.command.merchandise.SaveProduct
import product.model.type.DisplayOrder
import product.model.type.ProductNames
import java.util.*

/**
 * 商品の追加オーケストレーション 責務:
 * - API層からのリクエストスキーマをドメインモデルに変換
 * - リードモデルの読み込み
 * - 集約に対するコマンドの実行(複数集約をオーケストレーションする場合もある)
 * - 集約が返したイベントのリードモデルへの投影
 */
fun addProductOrchestration(
    request: AddProductRequest,
    // リードモデル
    readProductNames: ReadProductNames,
    readDisplayOrder: ReadDisplayOrders,
    // リポジトリ
    saveMerchandise: SaveProduct,
    // 現在のMerchandise状態を取得するためのリポジトリ関数を追加
    restoreMerchandiseState: () -> Merchandise,
): Result<Merchandise.Open.Added, AddProductError> = binding {
    // リクエストスキーマ -> ドメインモデル
    val productMetaData = request.toProductMetaData().mapError { AddProductError.InvalidRequest(it) }.bind()

    // リードモデル解決
    val productNames = readProductNames()
    val displayOrder = readDisplayOrder()
    
    // 現在のMerchandise集約の状態を取得
    val currentMerchandise = restoreMerchandiseState()
    
    // 現在の状態に応じたコマンド実行
    val productAdded = currentMerchandise
        .tryAddProduct(
            metaData = productMetaData,
            displayOrder = displayOrder,
            allProductNames = productNames
        ).mapError { AddProductError.DomainError(it) }.bind()

    val pushedEvent = publishEvent(productAdded) // イベントの発行

    // 集約の状態を保存。 イベント -> ステートの投影
    saveMerchandise(pushedEvent.product, pushedEvent.displayOrder)

    pushedEvent
}

// Merchandiseの拡張関数として実装
private fun Merchandise.tryAddProduct(
    metaData: Product.ProductMetaData,
    displayOrder: DisplayOrder,
    allProductNames: ProductNames
): Result<Merchandise.Open.Added, Merchandise.Open.MerchandiseError> {
    return when (this) {
        is Merchandise.Empty -> addProduct(metaData, displayOrder, allProductNames)
        is Merchandise.Open -> addProduct(metaData, displayOrder, allProductNames)
        is Merchandise.Suspended -> {
            // 入荷停止中は商品の追加ができないためエラーを返す
            Err(Merchandise.Open.MerchandiseError.OperationNotAllowed(
                "商品の追加ができません。システムは現在入荷停止中です: ${this.reason}"
            ))
        }
    }
}

/**
 * 商品追加 APIリクエストスキーマ
 * 本来は別のところに置くかも
 */
data class AddProductRequest(
    val name: String,
    val description: String,
    val category: String
) {
    internal fun toProductMetaData(): Result<Product.ProductMetaData, String> = binding {
        val name = NonEmptyString.from(name)
            .mapError { "Invalid product name: $name" }.bind()

        val description = NonEmptyString.from(description)
            .mapError { "Invalid product description: $description" }.bind()

        val category = NonEmptyString.from(category)
            .mapError { "Invalid product category: $category" }.bind()

        Product.ProductMetaData(
            productId =
                ID.from(NonEmptyString.from(UUID.randomUUID().toString())
                    .getOrThrow { IllegalStateException(it) }),
            name = name,
            description = description,
            category = category
        )
    }
}

sealed interface AddProductError {
    data class InvalidRequest(val message: String) : AddProductError
    data class DomainError(val error: Merchandise.Open.MerchandiseError) : AddProductError
}

fun <T> publishEvent(event: T): T {
    return event
}