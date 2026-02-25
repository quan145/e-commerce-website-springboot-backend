package com.poly.bezbe.service.impl;

import com.poly.bezbe.dto.request.product.BrandRequestDTO;
import com.poly.bezbe.dto.response.product.BrandResponseDTO;
import com.poly.bezbe.dto.response.PageResponseDTO;
import com.poly.bezbe.entity.Brand;
import com.poly.bezbe.entity.Product;
import com.poly.bezbe.exception.DuplicateResourceException; // Exception cho tên bị trùng
import com.poly.bezbe.exception.ResourceNotFoundException;
import com.poly.bezbe.repository.BrandRepository;
import com.poly.bezbe.repository.ProductRepository;
import com.poly.bezbe.service.BrandService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BrandServiceImpl implements BrandService {

    private final BrandRepository brandRepository;  // Làm việc với bảng Brand
    private final ProductRepository productRepository; // Làm việc với bảng Product

    // =============================== MAPPING ENTITY -> DTO ===============================
    // Chuyển Brand entity sang BrandResponseDTO + tính số sản phẩm thuộc thương hiệu
    private BrandResponseDTO mapToBrandDTO(Brand brand) {
        long productCount = productRepository.countByBrandId(brand.getId()); // Đếm số SP của brand

        return BrandResponseDTO.builder()
                .id(brand.getId())
                .name(brand.getName())
                .description(brand.getDescription())
                .active(brand.isActive())
                .imageUrl(brand.getImageUrl())
                .productCount(productCount)
                .build();
    }

    // =============================== LẤY DANH SÁCH BRAND + TÌM KIẾM + LỌC ===============================

    /**
     * Lấy danh sách brand theo phân trang, có hỗ trợ tìm kiếm + lọc ACTIVE/INACTIVE/ALL
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponseDTO<BrandResponseDTO> getAllBrands(Pageable pageable, String searchTerm, String status) {
        Page<Brand> brandPage;

        boolean searching = searchTerm != null && !searchTerm.isBlank(); // Có nhập từ khóa không
        boolean activeFilter = !"INACTIVE".equalsIgnoreCase(status); // true nếu ACTIVE

        // Nếu chọn ALL → không lọc theo trạng thái
        if ("ALL".equalsIgnoreCase(status)) {
            if (searching)
                brandPage = brandRepository.findByNameContainingIgnoreCase(searchTerm.trim(), pageable);
            else
                brandPage = brandRepository.findAll(pageable);
        } else {
            // Nếu lọc active/inactive kèm tìm kiếm
            if (searching)
                brandPage = brandRepository.findByNameContainingIgnoreCaseAndActive(
                        searchTerm.trim(), activeFilter, pageable
                );
            else
                brandPage = brandRepository.findAllByActive(activeFilter, pageable);
        }

        // Chuyển list Brand -> DTO
        List<BrandResponseDTO> dtos = brandPage.getContent().stream()
                .map(this::mapToBrandDTO)
                .collect(Collectors.toList());

        // Trả kết quả phân trang
        return new PageResponseDTO<>(
                dtos,
                brandPage.getNumber(),
                brandPage.getSize(),
                brandPage.getTotalElements(),
                brandPage.getTotalPages()
        );
    }

    // =============================== LẤY DANH SÁCH BRAND CHỈ ACTIVE ===============================

    /**
     * Lấy danh sách brand active, sắp xếp theo tên → dùng để fill dropdown chọn brand
     */
    @Override
    @Transactional(readOnly = true)
    public List<BrandResponseDTO> getAllBrandsBrief() {
        return brandRepository.findAllByActiveTrue(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(this::mapToBrandDTO)
                .collect(Collectors.toList());
    }

    // =============================== THÊM THƯƠNG HIỆU ===============================

    /**
     * Tạo thương hiệu mới + kiểm tra tên trùng
     */
    @Override
    @Transactional
    public BrandResponseDTO createBrand(BrandRequestDTO request) {
        String name = request.getName().trim(); // Chuẩn hóa tên

        // Kiểm tra tên đã tồn tại hay chưa
        if (brandRepository.existsByNameIgnoreCase(name)) {
            throw new DuplicateResourceException("Tên thương hiệu '" + name + "' đã tồn tại.");
        }

        // Tạo mới entity Brand
        Brand brand = Brand.builder()
                .name(name)
                .description(request.getDescription())
                .imageUrl(request.getImageUrl())
                .active(request.isActive())
                .build();

        Brand saved = brandRepository.save(brand); // Lưu vào DB
        return mapToBrandDTO(saved); // Trả về DTO
    }

    // =============================== CẬP NHẬT THƯƠNG HIỆU ===============================

    /**
     * Cập nhật brand theo ID + kiểm tra trùng tên với brand khác
     */
    @Override
    @Transactional
    public BrandResponseDTO updateBrand(Long id, BrandRequestDTO request) {
        Brand brand = brandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy Thương hiệu: " + id));

        String name = request.getName().trim();

        // Nếu tên mới khác tên hiện tại và trùng với brand khác → báo lỗi
        if (!brand.getName().equalsIgnoreCase(name)
                && brandRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {

            throw new DuplicateResourceException("Tên thương hiệu '" + name + "' đã được sử dụng.");
        }

        // Cập nhật dữ liệu
        brand.setName(name);
        brand.setDescription(request.getDescription());
        brand.setActive(request.isActive());
        brand.setImageUrl(request.getImageUrl());

        Brand updated = brandRepository.save(brand);
        return mapToBrandDTO(updated);
    }

    // =============================== XÓA MỀM (SOFT DELETE) ===============================

    /**
     * Xóa mềm brand → set active = false
     * Đồng thời ẩn toàn bộ sản phẩm thuộc brand đó
     */
    @Override
    @Transactional
    public void deleteBrand(Long id) {
        Brand brand = brandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy Thương hiệu: " + id));

        brand.setActive(false); // Xóa mềm brand
        brandRepository.save(brand);

        // Đồng thời ẩn luôn sản phẩm thuộc brand
        List<Product> productsToHide = productRepository.findAllByBrandIdAndActive(id, true);
        if (!productsToHide.isEmpty()) {
            for (Product product : productsToHide) {
                product.setActive(false);
            }
            productRepository.saveAll(productsToHide); // Lưu list product
        }
    }

    // =============================== XÓA VĨNH VIỄN (PERMANENT DELETE) ===============================

    /**
     * Xóa vĩnh viễn thương hiệu khỏi DB
     * Chỉ cho xóa nếu brand KHÔNG có sản phẩm nào
     */
    @Override
    @Transactional
    public void permanentDeleteBrand(Long id) {
        Brand brand = brandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy Thương hiệu: " + id));

        long productCount = productRepository.countByBrandId(id);

        // Nếu còn sản phẩm → không cho xóa để tránh lỗi khóa ngoại
        if (productCount > 0) {
            throw new IllegalStateException(
                    "Không thể xóa vĩnh viễn thương hiệu đang có " + productCount + " sản phẩm."
            );
        }

        brandRepository.delete(brand); // Xóa khỏi DB
    }
}
