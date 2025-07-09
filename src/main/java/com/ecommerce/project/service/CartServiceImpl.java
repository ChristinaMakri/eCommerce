package com.ecommerce.project.service;

import com.ecommerce.project.exceptions.APIException;
import com.ecommerce.project.exceptions.ResourceNotFoundException;
import com.ecommerce.project.model.Cart;
import com.ecommerce.project.model.CartItem;
import com.ecommerce.project.model.Product;
import com.ecommerce.project.payload.CartDTO;
import com.ecommerce.project.payload.ProductDTO;
import com.ecommerce.project.repositories.CartItemRepository;
import com.ecommerce.project.repositories.CartRepository;
import com.ecommerce.project.repositories.ProductRepository;
import com.ecommerce.project.util.AuthUtil;
import jakarta.transaction.Transactional;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CartServiceImpl implements CartService{

    @Autowired
    CartRepository cartRepository;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    CartItemRepository cartItemRepository;

    @Autowired
    ModelMapper modelMapper;

    @Autowired
    AuthUtil authUtil;

    @Override
    public CartDTO addProductToCart(Long productId, Integer quantity) {
        Cart cart = createCart();

        Product product = productRepository.findById(productId).orElseThrow(
                () -> new ResourceNotFoundException("Product","productId",productId)
        );

        if (product.getQuantity() == 0) {
            throw new APIException(product.getProductName() + " is out of stock");
        }

        if (product.getQuantity() < quantity) {
            throw new APIException("Please make an order of the " + product.getProductName() + " less than or equal to " + product.getQuantity());
        }

        CartItem cartItem = cartItemRepository.findCartItemByProductIdAndCartId(cart.getCartId(), product.getProductId());

        if (cartItem == null) {
            CartItem newCartItem = new CartItem();
            newCartItem.setProduct(product);
            newCartItem.setCart(cart);
            newCartItem.setQuantity(quantity);
            newCartItem.setDiscount(product.getDiscount());
            newCartItem.setProductPrice(product.getSpecialPrice());

            cartItemRepository.save(newCartItem);
            cart.getCartItems().add(newCartItem);
        } else {
            // If the product already exists in the cart, update the quantity
            cartItem.setQuantity(cartItem.getQuantity() + quantity);
            cartItemRepository.save(cartItem);
        }

        product.setQuantity(product.getQuantity() - quantity); // Decrease the quantity by the amount added to the cart
        productRepository.save(product);

        cart.setTotalPrice(cart.getTotalPrice() + (product.getSpecialPrice() * quantity));
        cartRepository.save(cart);

        // Map Cart to DTO and Prepare Response
        CartDTO cartDto = modelMapper.map(cart, CartDTO.class);

        List<CartItem> cartItems = cart.getCartItems();

        Stream<ProductDTO> productDtoStream = cartItems.stream().map(item -> {
            ProductDTO productDto = modelMapper.map(item.getProduct(), ProductDTO.class);
            productDto.setQuantity(item.getQuantity());
            return productDto;
        });

        cartDto.setProducts(productDtoStream.toList());

        return cartDto;
    }

    @Override
    public List<CartDTO> getAllCarts() {
        List<Cart> carts = cartRepository.findAll();
        if(carts.isEmpty()) throw new APIException("No cart exist.");
        List<CartDTO> cartDTOs = carts.stream()
                .map(cart -> {
                    CartDTO cartDTO = modelMapper.map(cart, CartDTO.class);
                    List<ProductDTO> products = cart.getCartItems().stream()
                            .map(p -> modelMapper.map(p.getProduct(), ProductDTO.class))
                            .toList();
                    cartDTO.setProducts(products);
                    return cartDTO;
                }).toList();
        return cartDTOs;
    }

    @Override
    public CartDTO getCart(String email, Long cartId) {
        Cart cart = cartRepository.findCartByEmailAndCartId(email,cartId);
        if(cart==null) throw new ResourceNotFoundException("Cart","cartId",cartId);
        CartDTO cartDTO = modelMapper.map(cart, CartDTO.class);
        cart.getCartItems().forEach(c ->
                c.getProduct().setQuantity(c.getQuantity()));
        List<ProductDTO> products = cart.getCartItems().stream()
                .map(p -> modelMapper.map(p.getProduct(), ProductDTO.class))
                .toList();
        cartDTO.setProducts(products);
        return cartDTO;
    }

    @Transactional
    @Override
    public CartDTO updateProductQuantityInCart(Long productId, int quantity) {
        String email = authUtil.loggedInEmail();
        Cart userCart = cartRepository.findCartByEmail(email);
        Long cartId = userCart.getCartId();

        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart","cartId",cartId));

        Product product = productRepository.findById(productId).orElseThrow(
                () -> new ResourceNotFoundException("Product","productId",productId)
        );

        if (product.getQuantity() == 0) {
            throw new APIException(product.getProductName() + " is out of stock");
        }

        if (product.getQuantity() < quantity) {
            throw new APIException("Please make an order of the "
                    + product.getProductName() + " less than or equal to "
                    + product.getQuantity());
        }

        CartItem cartItem = cartItemRepository.findCartItemByProductIdAndCartId(cartId, productId);
        if (cartItem == null) {
            throw new APIException("Product " +product.getProductName()
            +" not available in the cart.");
        }

        int newQuantity = cartItem.getQuantity() + quantity;
        if(newQuantity<0) {
            throw new APIException("The resulting quantity cannot be neagtive.");
        }

        if(newQuantity == 0) {
            deleteProductFromCart(cartId,productId);
        } else {
            cartItem.setProductPrice(product.getSpecialPrice());
            cartItem.setQuantity(cartItem.getQuantity() + quantity);
            cartItem.setDiscount(product.getDiscount());
            cart.setTotalPrice(cart.getTotalPrice() + (cartItem.getProductPrice() * quantity));
            cartRepository.save(cart);
        }
        CartItem updatedCartItem = cartItemRepository.save(cartItem);

        if(updatedCartItem.getQuantity() == 0) {
            cartItemRepository.deleteById(updatedCartItem.getCartItemId());
        }

        CartDTO cartDTO = modelMapper.map(cart, CartDTO.class);
        List<CartItem> cartItems = cart.getCartItems();
        Stream<ProductDTO> products = cartItems.stream().map(item -> {
            ProductDTO prd = modelMapper.map(item.getProduct(), ProductDTO.class);
            prd.setQuantity(item.getQuantity());
            return prd;
        });
        cartDTO.setProducts(products.toList());
        return cartDTO;
    }

    @Transactional
    @Override
    public String deleteProductFromCart(Long cartId, Long productId) {
        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart","cartId",cartId));

        CartItem cartItem = cartItemRepository.findCartItemByProductIdAndCartId(cartId,productId);

        if(cartItem == null) {
            throw new ResourceNotFoundException("Product","productId",productId);
        }

        cart.setTotalPrice(cart.getTotalPrice() -
                (cartItem.getProductPrice() * cartItem.getQuantity()));
        cartItemRepository.deleteCartItemByProductIdAndCartId(cartId,productId);

        return "Product " +cartItem.getProduct().getProductName() + " removed from the cart.";
    }

    @Override
    public void updateProductInCarts(Long cartId, Long productId) {
        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart","cartId",cartId));

        Product product = productRepository.findById(productId).orElseThrow(
                () -> new ResourceNotFoundException("Product","productId",productId)
        );

        CartItem cartItem = cartItemRepository.findCartItemByProductIdAndCartId(cartId,productId);

        if(cartItem == null) {
            throw new APIException("Product" +product.getProductName() + " not available in the cart.");
        }

        double cartPrice = cart.getTotalPrice() -
                (cartItem.getProductPrice() * cartItem.getQuantity());
        cartItem.setProductPrice(product.getSpecialPrice());
        cart.setTotalPrice(cartPrice +
                (cartItem.getProductPrice() * cartItem.getQuantity()));

        cartItem = cartItemRepository.save(cartItem);
    }

    private Cart createCart(){
        Cart userCart = cartRepository.findCartByEmail(authUtil.loggedInEmail());
        if(userCart != null) {
            return userCart;
        }

        Cart cart = new Cart();
        cart.setTotalPrice(0.00);
        cart.setUser(authUtil.loggedInUser());
        return cartRepository.save(cart);
    }
}
